package com.yamking.iptv

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import com.yamking.iptv.data.AdultFilter
import com.yamking.iptv.data.BlindScanProgress
import com.yamking.iptv.data.BlindScanRequest
import com.yamking.iptv.data.BlindScanner
import com.yamking.iptv.data.Channel
import com.yamking.iptv.data.M3uParser
import com.yamking.iptv.data.SavedState
import com.yamking.iptv.data.ScanSource
import com.yamking.iptv.data.StreamChecker
import com.yamking.iptv.data.TvList
import com.yamking.iptv.data.TvListFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.random.Random

data class ScanProgress(val done: Int, val total: Int, val valid: Int)

const val DEFAULT_PLAYLIST_URL =
    "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist_zz_movies.m3u8"

private const val STATE_FILE = "state.json"
private const val PARALLEL_CHECKS = 32
private const val BACKGROUND_CHECKS = 8
private const val RECHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
private const val MAX_RECENTS = 30

/**
 * Bibliothèque unique de chaînes : chaque import de playlist y AJOUTE les chaînes
 * dont le flux répond, sans toucher à celles déjà présentes.
 */
class AppState(private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    private val checker = StreamChecker()
    private var job: Job? = null
    private var lastScanAt = 0L

    /** Toutes les chaînes déjà validées au moins une fois. */
    var library by mutableStateOf<List<Channel>>(emptyList())
        private set
    var validUrls by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Adresses des favoris, dans l'ordre choisi par l'utilisateur. */
    // Comparaison par référence : deux ensembles de mêmes éléments dans un ordre différent sont « égaux »
    // pour Kotlin, et un simple changement d'ordre serait sinon ignoré.
    var favorites by mutableStateOf<Set<String>>(emptySet(), referentialEqualityPolicy())
        private set

    /** Adresses des dernières chaînes regardées, la plus récente d'abord. */
    var recents by mutableStateOf<List<String>>(emptyList())
        private set

    /** Chaînes d'un import interrompu qui restent à vérifier. */
    var pending by mutableStateOf<List<Channel>>(emptyList())
        private set
    var scan by mutableStateOf<ScanProgress?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** Message d'information (résultat d'un import, d'une restauration…). */
    var info by mutableStateOf<String?>(null)

    /** Qualité par défaut (hauteur en pixels) ; null = automatique. */
    var maxHeight by mutableStateOf<Int?>(null)
        private set

    // Verrou de la RECHERCHE de contenu pour adultes (il ne touche ni la bibliothèque ni les imports). Déclaré ici, avant le bloc init qui relit la sauvegarde :
    // une propriété déclarée plus bas serait réinitialisée juste après.
    private var adultPinHash: String? = null
    private var adultPinSalt: String? = null

    /** Un code a déjà été défini pour le contenu pour adultes. */
    var hasAdultPin by mutableStateOf(false)
        private set

    /** Recherche pour adultes déverrouillée pour cette session : le verrou se referme à chaque redémarrage. */
    var adultUnlocked by mutableStateOf(false)
        private set

    /** Seules les chaînes dont l'adresse a répondu au dernier contrôle sont visibles. */
    val channels: List<Channel> by derivedStateOf { library.filter { it.url in validUrls } }

    val isEmpty: Boolean get() = library.isEmpty()

    /** Dernière chaîne regardée, si elle est toujours disponible. */
    val lastWatched: Channel? get() = recents.firstOrNull()?.let { url -> channels.firstOrNull { it.url == url } }

    init {
        runCatching { Storage.read(STATE_FILE)?.let { json.decodeFromString<SavedState>(it) } }
            .getOrNull()
            ?.let(::apply)
        // Recontrôle quotidien : à l'ouverture, si le dernier contrôle complet date de plus de 24 h.
        if (library.isNotEmpty() && nowMillis() - lastScanAt > RECHECK_INTERVAL_MS) {
            job = scope.launch { runScan(library, adding = false, parallel = BACKGROUND_CHECKS) }
        }
    }

    private fun apply(saved: SavedState) {
        // Les anciennes sauvegardes contenaient aussi les chaînes non valides : on ne les reprend pas.
        library = if (saved.version >= 2) saved.all else saved.all.filter { it.url in saved.validUrls }
        validUrls = saved.validUrls
        favorites = saved.favorites
        recents = saved.recents
        pending = saved.pending
        maxHeight = saved.maxHeight
        lastScanAt = saved.lastScanAt
        adultPinHash = saved.adultPinHash
        adultPinSalt = saved.adultPinSalt
        hasAdultPin = saved.adultPinHash != null
    }

    // ---- Verrou « contenu pour adultes » -----------------------------------------------------

    /** Définit le code (4 chiffres au moins) et lève le verrou. Seule son empreinte est enregistrée. */
    fun setAdultPin(pin: String): Boolean {
        if (pin.length < 4 || hasAdultPin) return false
        val salt = (1..16).joinToString("") { "0123456789abcdef"[Random.nextInt(16)].toString() }
        adultPinSalt = salt
        adultPinHash = sha256Hex(salt + pin)
        hasAdultPin = true
        adultUnlocked = true
        save()
        return true
    }

    /** Lève le verrou si [pin] est le bon code. */
    fun unlockAdult(pin: String): Boolean {
        val ok = adultPinHash != null && sha256Hex(adultPinSalt.orEmpty() + pin) == adultPinHash
        if (ok) adultUnlocked = true
        return ok
    }

    fun lockAdult() {
        adultUnlocked = false
    }

    // ---- Import ----------------------------------------------------------------------------

    fun importFromUrl(url: String) {
        val clean = url.trim()
        if (clean.isEmpty() || busy) return
        job = scope.launch {
            loading = true
            error = null
            info = null
            try {
                val text = withContext(Dispatchers.Default) { checker.download(clean) }
                loading = false
                importText(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val reason = e.message.orEmpty()
                error = when {
                    "timeout" in reason.lowercase() -> "Le téléchargement de la playlist a pris trop de temps. Vérifiez la connexion et réessayez."
                    reason.startsWith("Le serveur") -> "Impossible de charger la playlist. $reason."
                    else -> "Impossible de charger la playlist. Vérifiez l'adresse et la connexion."
                }
            } finally {
                loading = false
            }
        }
    }

    /** [text] est une playlist M3U, ou une sauvegarde complète de l'application (JSON). */
    fun importFromText(text: String) {
        if (busy) return
        error = null
        info = null
        if (text.trimStart('﻿', ' ', '\r', '\n').startsWith("{")) {
            // Du JSON : soit une liste « .tvlist » (ajout à la bibliothèque), soit une sauvegarde complète.
            if (TvListFormat.read(text) == null) {
                restore(text)
                return
            }
        }
        job = scope.launch { importText(text) }
    }

    fun resumePending() {
        val rest = pending
        if (rest.isEmpty() || busy) return
        job = scope.launch {
            error = null
            val added = runScan(rest, adding = true)
            info = "$added chaînes ajoutées."
        }
    }

    fun dismissPending() {
        pending = emptyList()
        save()
    }

    /** Recontrôle toute la bibliothèque : les chaînes qui ne répondent plus sont masquées. */
    fun rescan() {
        if (library.isEmpty() || busy) return
        job = scope.launch {
            error = null
            info = null
            runScan(library, adding = false)
        }
    }

    fun cancelScan() {
        job?.cancel()
    }

    // ---- Gestion de la bibliothèque ---------------------------------------------------------

    fun toggleFavorite(channel: Channel) {
        favorites = if (channel.url in favorites) favorites - channel.url else favorites + channel.url
        save()
    }

    /** Déplace un favori d'un cran vers le début ([delta] = -1) ou la fin (+1). */
    fun moveFavorite(channel: Channel, delta: Int) {
        val order = favorites.toMutableList()
        val from = order.indexOf(channel.url)
        val to = from + delta
        if (from < 0 || to !in order.indices) return
        order[from] = order[to]
        order[to] = channel.url
        favorites = order.toCollection(LinkedHashSet())
        save()
    }

    fun markPlayed(channel: Channel) {
        recents = (listOf(channel.url) + recents.filter { it != channel.url }).take(MAX_RECENTS)
        save()
    }

    /** Dernière chaîne supprimée, tant que la suppression peut encore être annulée. */
    var lastDeleted by mutableStateOf<Channel?>(null)
        private set
    private var deletedIndex = 0
    private var deletedWasFavorite = false

    /** Supprime sans demander confirmation : l'action s'annule d'un geste avec [undoDelete]. */
    fun delete(channel: Channel) {
        deletedIndex = library.indexOfFirst { it.url == channel.url }
        if (deletedIndex < 0) return
        deletedWasFavorite = channel.url in favorites
        lastDeleted = channel
        library = library.filter { it.url != channel.url }
        validUrls = validUrls - channel.url
        favorites = favorites - channel.url
        recents = recents - channel.url
        save()
    }

    /** Remet la chaîne supprimée à sa place, avec son statut de favori. */
    fun undoDelete() {
        val channel = lastDeleted ?: return
        library = library.toMutableList().apply { add(deletedIndex.coerceAtMost(size), channel) }
        validUrls = validUrls + channel.url
        if (deletedWasFavorite) favorites = favorites + channel.url
        lastDeleted = null
        save()
    }

    fun forgetDeleted() {
        lastDeleted = null
    }

    fun clearLibrary() {
        job?.cancel()
        library = emptyList()
        validUrls = emptySet()
        favorites = emptySet()
        recents = emptyList()
        pending = emptyList()
        // Seul recours si le code est oublié : tout effacer remet aussi le verrou à zéro.
        adultPinHash = null
        adultPinSalt = null
        hasAdultPin = false
        adultUnlocked = false
        lastDeleted = null
        error = null
        info = null
        save()
    }

    fun edit(channel: Channel, name: String, groups: List<String>) {
        val cleanName = name.trim().ifEmpty { return }
        val cleanGroups = groups.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        library = library.map { if (it.url == channel.url) it.copy(name = cleanName, groups = cleanGroups) else it }
        save()
    }

    fun updateMaxHeight(value: Int?) {
        maxHeight = value
        save()
    }

    // ---- Export et sauvegarde ---------------------------------------------------------------

    /** Contenu M3U des chaînes actuellement valides, pour l'export. */
    fun exportM3u(): String = M3uParser.write(channels)

    /** Catégories exportables avec leur nombre de chaînes, les plus fournies d'abord. */
    fun exportCategories(): List<Pair<String, Int>> {
        val counts = channels.flatMap { it.groups }.groupingBy { it }.eachCount()
        val without = channels.count { it.groups.isEmpty() }
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value } +
            (if (without > 0) listOf(TvListFormat.NO_CATEGORY to without) else emptyList())
    }

    /** Chaînes qu'un export des [categories] contiendrait. */
    fun exportSelection(categories: Set<String>, favoritesOnly: Boolean): List<Channel> =
        TvListFormat.select(channels, categories, favorites, favoritesOnly)

    /** Contenu d'un fichier « .tvlist » limité aux [categories] choisies. */
    fun exportTvList(categories: Set<String>, favoritesOnly: Boolean): String {
        val selected = exportSelection(categories, favoritesOnly)
        return TvListFormat.write(
            TvList(
                exportedAt = nowMillis(),
                categories = categories.toList(),
                channels = selected,
                favorites = selected.map { it.url }.filter { it in favorites },
            )
        )
    }

    /** Sauvegarde complète : chaînes, favoris, récents et réglages. */
    fun backupJson(): String = json.encodeToString(SavedState.serializer(), snapshot())

    private fun restore(text: String) {
        val saved = runCatching { json.decodeFromString<SavedState>(text) }.getOrNull()
        if (saved == null || saved.all.isEmpty()) {
            error = "Ce fichier n'est pas une sauvegarde valide."
            return
        }
        job?.cancel()
        apply(saved)
        save()
        info = "Sauvegarde restaurée : ${channels.size} chaînes."
    }

    // ---- Scan -------------------------------------------------------------------------------

    private val busy: Boolean get() = loading || scan != null || blindProgress != null

    // ---- Recherche automatique de chaînes ----------------------------------------------------

    private val blindScanner = BlindScanner(checker)
    private var blindJob: Job? = null

    /** Avancement de la recherche en cours ; null quand aucune recherche ne tourne. */
    var blindProgress by mutableStateOf<BlindScanProgress?>(null)
        private set

    /** Nombre de chaînes demandé pour la recherche en cours ou la dernière terminée. */
    var blindTarget by mutableStateOf(0)
        private set

    /** La recherche en cours ou la dernière terminée était une recherche lente (N chaînes par source). */
    var blindThorough by mutableStateOf(false)
        private set

    /** Chaînes trouvées et validées, en attente du choix de l'utilisateur. */
    var blindResults by mutableStateOf<List<Channel>>(emptyList())
        private set

    /** Une recherche s'est terminée (même sans résultat) et n'a pas encore été fermée. */
    var blindFinished by mutableStateOf(false)
        private set

    /** Recherche lente : sources recensées, en attente du choix de l'utilisateur. */
    var blindSources by mutableStateOf<List<ScanSource>>(emptyList())
        private set

    /** Recherche lente : le recensement des sources est en cours (avant le choix). */
    var blindDiscovering by mutableStateOf(false)
        private set

    fun startBlindScan(asked: BlindScanRequest) {
        if (busy) return
        // C'est l'état du verrou qui décide, jamais la fenêtre qui lance la recherche.
        val request = asked.copy(allowAdult = adultUnlocked)
        blindResults = emptyList()
        blindSources = emptyList()
        blindFinished = false
        blindTarget = request.target
        blindThorough = request.thorough
        blindProgress = BlindScanProgress()
        if (request.thorough) {
            // Recherche lente, étape 1 : on recense les sources, puis l'utilisateur choisit lesquelles fouiller.
            blindDiscovering = true
            blindJob = scope.launch {
                try {
                    val sources = withContext(Dispatchers.Default) {
                        blindScanner.discover(request) { blindProgress = it }
                    }
                    blindSources = sources
                    if (sources.isEmpty()) blindFinished = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    error = "La recherche a été interrompue par une erreur réseau."
                } finally {
                    blindProgress = null
                    blindDiscovering = false
                }
            }
            return
        }
        val known = library.mapTo(HashSet()) { it.url }
        runBlind {
            blindScanner.run(
                request = request,
                knownUrls = known,
                onProgress = { blindProgress = it },
                onFound = { blindResults = blindResults + it },
            )
        }
    }

    /** Recherche lente, étape 2 : cherche [blindTarget] chaînes valides dans chacune des sources choisies. */
    fun scanBlindSources(selected: List<ScanSource>) {
        if (busy || selected.isEmpty()) return
        blindSources = emptyList()
        blindProgress = BlindScanProgress(sourcesTotal = selected.size)
        val known = library.mapTo(HashSet()) { it.url }
        runBlind {
            blindScanner.scan(
                sources = selected,
                perSource = blindTarget,
                knownUrls = known,
                onProgress = { blindProgress = it },
                onFound = { blindResults = blindResults + it },
            )
        }
    }

    private fun runBlind(search: suspend () -> Unit) {
        blindJob = scope.launch {
            try {
                withContext(Dispatchers.Default) { search() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "La recherche a été interrompue par une erreur réseau."
            } finally {
                // Arrêtée ou terminée : ce qui a été trouvé reste proposé à l'utilisateur.
                blindProgress = null
                blindFinished = true
            }
        }
    }

    fun stopBlindScan() {
        blindJob?.cancel()
    }

    /** Ajoute à la bibliothèque les chaînes choisies ; elles viennent d'être testées, donc pas de nouveau scan. */
    fun addBlindResults(selected: List<Channel>) {
        val known = library.mapTo(HashSet()) { it.url }
        val fresh = selected.filter { it.url !in known }
        library = library + fresh
        validUrls = validUrls + fresh.map { it.url }
        info = if (fresh.size == 1) "1 chaîne ajoutée." else "${fresh.size} chaînes ajoutées."
        closeBlindScan()
        save()
    }

    fun closeBlindScan() {
        blindJob?.cancel()
        blindResults = emptyList()
        blindSources = emptyList()
        blindFinished = false
    }

    private suspend fun importText(text: String) {
        // Le texte est une liste « .tvlist » de l'application ou une playlist M3U.
        val tvList = if (text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("{")) TvListFormat.read(text) else null
        val parsed = withContext(Dispatchers.Default) { tvList?.channels ?: M3uParser.parse(text) }
        importChannels(parsed, tvList?.favorites.orEmpty())
    }

    /**
     * Ajoute à la bibliothèque les chaînes de [list] qui appartiennent aux [categories] choisies
     * (liste par défaut de l'application, par exemple). Elles sont testées avant d'entrer.
     */
    fun importTvList(list: TvList, categories: Set<String>) {
        if (busy) return
        error = null
        info = null
        val chosen = TvListFormat.select(list.channels, categories, emptySet(), favoritesOnly = false)
        job = scope.launch { importChannels(chosen, list.favorites) }
    }

    /** Écarte les chaînes déjà présentes et les doublons, teste le reste, et ajoute ce qui répond. */
    private suspend fun importChannels(channels: List<Channel>, favoriteUrls: List<String>) {
        val knownUrls = library.mapTo(HashSet()) { it.url }
        // Même identifiant et même nom : c'est la même chaîne arrivée par une autre adresse.
        val knownKeys = library.mapNotNullTo(HashSet()) { duplicateKey(it) }
        val parsed = channels.distinctBy { it.url }
        val notHere = parsed.filter { it.url !in knownUrls }
        val seen = HashSet<String>()
        val fresh = notHere.filter { channel ->
            val key = duplicateKey(channel) ?: return@filter true
            key !in knownKeys && seen.add(key)
        }
        val duplicates = notHere.size - fresh.size
        when {
            parsed.isEmpty() -> error = "Aucune chaîne trouvée dans cette liste."
            fresh.isEmpty() -> error = "Toutes les chaînes de cette liste sont déjà dans votre bibliothèque."
            else -> {
                val added = runScan(fresh, adding = true)
                if (added == 0) {
                    error = "Aucune nouvelle chaîne valide dans cette liste."
                } else {
                    // Une liste « .tvlist » transporte aussi les favoris : on les remet sur les chaînes ajoutées.
                    val restored = favoriteUrls.filter { it in validUrls && it !in favorites }
                    if (restored.isNotEmpty()) {
                        favorites = favorites + restored
                        save()
                    }
                    info = "$added chaînes ajoutées" +
                        (if (duplicates > 0) ", $duplicates doublons ignorés." else ".")
                }
            }
        }
    }

    private fun duplicateKey(channel: Channel): String? =
        channel.tvgId?.let { it.lowercase() + "|" + channel.name.lowercase() }

    /** Teste [targets] ; renvoie le nombre de chaînes valides. */
    private suspend fun runScan(targets: List<Channel>, adding: Boolean, parallel: Int = PARALLEL_CHECKS): Int {
        val baseLibrary = library
        val baseValid = validUrls
        val checked = HashSet<String>()
        val found = HashSet<String>()
        val results = arrayOfNulls<Channel>(targets.size)
        val lock = Mutex()
        var done = 0

        // Chaînes existantes intactes ; les nouvelles valides s'ajoutent dans l'ordre de la playlist.
        fun publish() {
            val updated = results.filterNotNull()
            library = if (adding) {
                baseLibrary + updated
            } else {
                // Recontrôle : on met seulement à jour la qualité relevée sur le flux.
                val byUrl = updated.associateBy { it.url }
                baseLibrary.map { byUrl[it.url]?.let { fresh -> it.copy(quality = fresh.quality) } ?: it }
            }
            validUrls = (baseValid - checked) + found
        }

        scan = ScanProgress(0, targets.size, 0)
        var completed = false
        try {
            withContext(Dispatchers.Default) {
                val permits = Semaphore(parallel)
                coroutineScope {
                    targets.forEachIndexed { index, channel ->
                        launch {
                            val stream = permits.withPermit { checker.check(channel.url) }
                            lock.withLock {
                                done++
                                checked += channel.url
                                if (stream != null) {
                                    found += channel.url
                                    results[index] = stream.maxHeight
                                        ?.let { channel.copy(quality = "${it}p") } ?: channel
                                }
                                // Sur des milliers de chaînes, on ne rafraîchit la grille que par paliers.
                                if ((stream != null && found.size <= 40) || done % 25 == 0) publish()
                                scan = ScanProgress(done, targets.size, found.size)
                            }
                        }
                    }
                }
            }
            completed = true
        } finally {
            // Même si le scan est arrêté en route, on garde ce qui a déjà été validé…
            publish()
            // …et on retient ce qui reste à vérifier, pour pouvoir reprendre l'import plus tard.
            if (adding) pending = if (completed) emptyList() else targets.filter { it.url !in checked }
            if (completed && !adding) lastScanAt = nowMillis()
            if (lastScanAt == 0L) lastScanAt = nowMillis()
            scan = null
            save()
        }
        return found.size
    }

    private fun snapshot() = SavedState(
        all = library,
        validUrls = validUrls,
        favorites = favorites,
        scanned = true,
        version = 2,
        maxHeight = maxHeight,
        recents = recents,
        lastScanAt = lastScanAt,
        pending = pending,
        adultPinHash = adultPinHash,
        adultPinSalt = adultPinSalt,
    )

    private fun save() {
        runCatching { Storage.write(STATE_FILE, json.encodeToString(SavedState.serializer(), snapshot())) }
    }
}
