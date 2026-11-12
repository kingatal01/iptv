package com.yamking.iptv.data

import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Annulation interne : le nombre de chaînes demandé est atteint. */
private class TargetReached : CancellationException("Nombre de chaînes atteint")

/**
 * Langue proposée à la recherche automatique.
 * [code] est le code ISO 639-3 utilisé par l'index iptv-org, [names] les libellés reconnus dans
 * l'attribut tvg-language, [countries] les pays (ISO à deux lettres) où cette langue domine et
 * [freeTv] les playlists par pays du projet Free-TV.
 */
class ScanLanguage(
    val code: String,
    val label: String,
    val names: List<String>,
    val countries: Set<String>,
    val freeTv: List<String>,
)

object ScanLanguages {
    val all = listOf(
        ScanLanguage("fra", "Français", listOf("French", "Français"), setOf("FR", "BE", "CH", "LU", "MC", "SN", "CI", "CM", "TD", "ML", "BF", "NE", "TG", "BJ", "GA", "CG", "CD", "MG", "HT"), listOf("france", "belgium", "switzerland")),
        ScanLanguage("eng", "Anglais", listOf("English"), setOf("US", "GB", "CA", "AU", "IE", "NZ", "NG", "GH", "KE", "ZA"), listOf("usa", "uk", "canada", "australia", "ireland")),
        ScanLanguage("ara", "Arabe", listOf("Arabic"), setOf("SA", "AE", "EG", "MA", "DZ", "TN", "QA", "KW", "LB", "IQ", "JO", "SY", "LY", "SD", "OM", "BH", "YE", "PS"), listOf("saudi_arabia", "egypt", "morocco", "qatar", "uae")),
        ScanLanguage("spa", "Espagnol", listOf("Spanish"), setOf("ES", "MX", "AR", "CO", "CL", "PE", "VE", "EC", "UY", "PY", "BO", "DO", "CR", "PA", "GT", "HN", "SV", "NI", "CU"), listOf("spain", "mexico", "argentina", "chile", "colombia")),
        ScanLanguage("por", "Portugais", listOf("Portuguese"), setOf("PT", "BR", "AO", "MZ"), listOf("portugal", "brazil")),
        ScanLanguage("deu", "Allemand", listOf("German"), setOf("DE", "AT"), listOf("germany", "austria")),
        ScanLanguage("ita", "Italien", listOf("Italian"), setOf("IT", "SM"), listOf("italy")),
        ScanLanguage("rus", "Russe", listOf("Russian"), setOf("RU", "BY", "KZ"), listOf("russia")),
        ScanLanguage("tur", "Turc", listOf("Turkish"), setOf("TR"), listOf("turkey")),
        ScanLanguage("hin", "Hindi", listOf("Hindi"), setOf("IN"), listOf("india")),
        ScanLanguage("zho", "Chinois", listOf("Chinese", "Mandarin", "Cantonese"), setOf("CN", "TW", "HK"), listOf("china", "taiwan", "hong_kong")),
    )
}

data class BlindScanRequest(
    val language: ScanLanguage,
    /** Nombre de chaînes valides à trouver : la recherche s'arrête dès qu'il est atteint. */
    val target: Int,
    /** Index publics de chaînes gratuites (iptv-org, Free-TV). */
    val usePublicIndexes: Boolean,
    /** Recherche de playlists sur le web (DuckDuckGo, Brave, Yahoo) et dans les dépôts publics (GitHub, GitLab). */
    val useWebSearch: Boolean,
    /** Mots-clés ajoutés par l'utilisateur pour la recherche web. */
    val keywords: List<String>,
    /** Garder aussi les chaînes trouvées sur le web dont ni la langue ni le pays ne sont connus. */
    val includeUnknownLanguage: Boolean,
    /**
     * false = recherche rapide : on s'arrête à [target] chaînes au total.
     * true = recherche lente : [target] chaînes dans chaque source, avec un tri strict sur les critères.
     */
    val thorough: Boolean = false,
    /**
     * Le verrou « contenu pour adultes » a été levé par l'utilisateur : ces chaînes et ces mots-clés
     * sont alors acceptés, et le filtrage des moteurs de recherche est désactivé.
     */
    val allowAdult: Boolean = false,
)

/** Source recensée par la recherche lente, avec ses chaînes qui correspondent aux critères (non encore testées). */
class ScanSource(val url: String, val label: String, val channels: List<Channel>)

data class BlindScanProgress(
    val stage: String = "",
    /** Playlists déjà parcourues. */
    val sources: Int = 0,
    /** Chaînes testées. */
    val tested: Int = 0,
    val found: Int = 0,
    /** Recherche lente : nombre de sources recensées (0 tant qu'il n'est pas connu). */
    val sourcesTotal: Int = 0,
)

/**
 * Recherche automatique de chaînes : collecte des playlists (index publics, puis résultats de
 * moteurs de recherche), n'en garde que les chaînes de la langue demandée, les teste, et s'arrête
 * dès que le nombre voulu est atteint. Rien n'est ajouté à la bibliothèque ici.
 */
class BlindScanner(private val checker: StreamChecker) {

    private class Source(val url: String, val label: String, /** La langue est garantie par la source. */ val trusted: Boolean)

    /** Garde l'avancement courant et le transmet à l'interface. */
    private class Reporter(private val onProgress: (BlindScanProgress) -> Unit) {
        var progress = BlindScanProgress()
            private set

        fun update(change: (BlindScanProgress) -> BlindScanProgress) {
            progress = change(progress)
            onProgress(progress)
        }
    }

    /** Télécharge [source] et renvoie ses chaînes qui correspondent aux critères ; null si ce n'est pas une liste de chaînes. */
    private suspend fun candidates(source: Source, request: BlindScanRequest): List<Channel>? {
        val text = runCatching { checker.download(source.url) }.getOrNull()
        if (text == null || !isChannelList(text)) return null
        return M3uParser.parse(text).distinctBy { it.url }
            // Verrou actif : les chaînes pour adultes ne sont jamais proposées.
            .filter { request.allowAdult || !AdultFilter.isAdult(it) }
            .mapNotNull { match(it, request, source.trusted) }
    }

    /**
     * Teste [channels] en parallèle jusqu'à [limit] chaînes valides. Chaque chaîne valide est signalée
     * aussitôt ; dès que le compte est bon, les tests restants sont annulés.
     */
    private suspend fun test(
        channels: List<Channel>,
        limit: Int,
        found: MutableList<Channel>,
        reporter: Reporter,
        onFound: (Channel) -> Unit,
    ) {
        if (limit <= 0) return
        val permits = Semaphore(PARALLEL)
        val lock = Mutex()
        var foundHere = 0
        try {
            coroutineScope {
                for (channel in channels) launch {
                    val stream = permits.withPermit { checker.check(channel.url) }
                    lock.withLock {
                        if (stream != null && foundHere < limit) {
                            val valid = stream.maxHeight?.let { channel.copy(quality = "${it}p") } ?: channel
                            found += valid
                            foundHere++
                            onFound(valid)
                        }
                        reporter.update { it.copy(tested = it.tested + 1, found = found.size) }
                        if (foundHere >= limit) this@coroutineScope.cancel(TargetReached())
                    }
                }
            }
        } catch (e: TargetReached) {
            // Arrêt voulu : le compte est bon. Une annulation venue de l'extérieur, elle, remonte.
        }
    }

    /** Playlists trouvées par les cinq sources de recherche, visitées une à une par [visit] tant qu'il renvoie true. */
    private suspend fun webPlaylists(request: BlindScanRequest, visit: suspend (Source) -> Boolean) {
        val visited = HashSet<String>()
        // Renvoie false quand il faut arrêter (compte atteint ou plafond de playlists).
        suspend fun offer(playlist: String): Boolean {
            if (visited.size >= MAX_WEB_PLAYLISTS) return false
            if (!visited.add(playlist)) return true
            return visit(Source(playlist, sourceLabel(playlist), trusted = false))
        }
        for (query in queries(request)) {
            // Moteurs web (DuckDuckGo, Brave, Yahoo) : chaque résultat est une page à visiter.
            for (result in searchWeb(query.web, request.allowAdult)) {
                for (playlist in playlistsBehind(result)) if (!offer(playlist)) return
            }
            // GitHub : la recherche de dépôts mène directement aux fichiers de playlists.
            for (playlist in gitHubPlaylists(query.repositories)) if (!offer(playlist)) return
        }
        for (playlist in gitLabPlaylists()) if (!offer(playlist)) return
    }

    /**
     * Recherche rapide : parcourt les sources dans l'ordre et s'arrête dès que [BlindScanRequest.target]
     * chaînes sont trouvées, toutes sources confondues.
     */
    suspend fun run(
        request: BlindScanRequest,
        knownUrls: Set<String>,
        onProgress: (BlindScanProgress) -> Unit,
        onFound: (Channel) -> Unit,
    ): List<Channel> {
        val found = mutableListOf<Channel>()
        val seen = HashSet(knownUrls)
        val reporter = Reporter(onProgress)

        suspend fun consume(source: Source) {
            reporter.update { it.copy(stage = source.label) }
            val channels = candidates(source, request) ?: return
            reporter.update { it.copy(sources = it.sources + 1) }
            test(channels.filter { seen.add(it.url) }, request.target - found.size, found, reporter, onFound)
        }

        if (request.usePublicIndexes) {
            for (source in publicSources(request.language)) {
                if (found.size >= request.target) break
                consume(source)
            }
        }
        if (request.useWebSearch && found.size < request.target) {
            reporter.update { it.copy(stage = "Recherche sur le web") }
            webPlaylists(request) { source ->
                consume(source)
                found.size < request.target
            }
        }
        reporter.update { it.copy(stage = "") }
        return found
    }

    /**
     * Recherche lente, première étape : recense toutes les sources et, pour chacune, les chaînes
     * qui correspondent aux critères. L'utilisateur choisit ensuite lesquelles fouiller avec [scan].
     */
    suspend fun discover(request: BlindScanRequest, onProgress: (BlindScanProgress) -> Unit): List<ScanSource> {
        val reporter = Reporter(onProgress)
        val raw = mutableListOf<Source>()
        if (request.usePublicIndexes) raw += publicSources(request.language)
        if (request.useWebSearch) {
            reporter.update { it.copy(stage = "Recherche sur le web") }
            webPlaylists(request) { raw += it; true }
        }
        // Le web retrouve souvent les mêmes playlists que les index publics : chaque adresse ne compte qu'une fois.
        val unique = raw.distinctBy { it.url }
        reporter.update { it.copy(sourcesTotal = unique.size) }
        val sources = mutableListOf<ScanSource>()
        for (source in unique) {
            reporter.update { it.copy(stage = source.label) }
            val channels = candidates(source, request)
            // Une adresse sans aucune chaîne correspondante n'est pas proposée.
            if (!channels.isNullOrEmpty()) sources += ScanSource(source.url, source.label, channels)
            reporter.update { it.copy(sources = it.sources + 1, found = sources.size) }
        }
        reporter.update { it.copy(stage = "") }
        return sources
    }

    /** Recherche lente, seconde étape : cherche [perSource] chaînes valides dans chacune des [sources] choisies. */
    suspend fun scan(
        sources: List<ScanSource>,
        perSource: Int,
        knownUrls: Set<String>,
        onProgress: (BlindScanProgress) -> Unit,
        onFound: (Channel) -> Unit,
    ): List<Channel> {
        val found = mutableListOf<Channel>()
        val seen = HashSet(knownUrls)
        val reporter = Reporter(onProgress)
        reporter.update { it.copy(sourcesTotal = sources.size) }
        for (source in sources) {
            reporter.update { it.copy(stage = source.label) }
            test(source.channels.filter { seen.add(it.url) }, perSource, found, reporter, onFound)
            reporter.update { it.copy(sources = it.sources + 1) }
        }
        reporter.update { it.copy(stage = "") }
        return found
    }

    // ---- Sources publiques ------------------------------------------------------------------

    private fun publicSources(language: ScanLanguage): List<Source> =
        listOf(Source("https://iptv-org.github.io/iptv/languages/${language.code}.m3u", "iptv-org · ${language.label}", true)) +
            language.freeTv.map {
                Source("https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist_$it.m3u8", "Free-TV · $it", true)
            }

    /**
     * Garde la chaîne si elle correspond aux critères, en lui attribuant la langue demandée.
     * En recherche lente le tri est strict : la langue ou le pays doivent être connus (hors index
     * par langue), et si des mots-clés sont donnés le nom ou une catégorie doit en contenir un.
     */
    private fun match(channel: Channel, request: BlindScanRequest, trusted: Boolean): Channel? {
        val language = request.language
        val tagged = channel.copy(language = language.names.first())
        val declared = channel.language
        val inLanguage = when {
            trusted -> tagged
            declared != null -> if (language.names.any { it.equals(declared, ignoreCase = true) }) tagged else null
            channel.country != null -> if (channel.country in language.countries) tagged else null
            else -> if (request.includeUnknownLanguage && !request.thorough) channel else null
        } ?: return null
        if (!request.thorough) return inLanguage
        val keywords = request.keywords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (keywords.isEmpty()) return inLanguage
        val texts = (channel.groups + channel.name).map { it.lowercase() }
        return if (keywords.any { keyword -> texts.any { keyword in it } }) inLanguage else null
    }

    // ---- Recherche web ----------------------------------------------------------------------

    /** Une recherche : sa formulation pour les moteurs web et sa formulation courte pour les dépôts de code. */
    private class Query(val web: String, val repositories: String)

    private fun queries(request: BlindScanRequest): List<Query> {
        val english = request.language.names.first()
        val label = request.language.label
        val defaults = listOf(
            Query("iptv m3u $english channels github", "iptv m3u $english"),
            Query("iptv $label m3u8 playlist", "iptv $label"),
        )
        // Chaque mot-clé de l'utilisateur donne sa propre recherche, en priorité.
        return request.keywords.map { it.trim() }.filter { it.isNotEmpty() }
            .filter { request.allowAdult || !AdultFilter.matches(it) }
            .map { Query("$it m3u", "iptv $it") } + defaults
    }

    /** Interroge les moteurs web en lisant leur page de résultats HTML ; renvoie les adresses des résultats. */
    internal suspend fun searchWeb(query: String, unfiltered: Boolean): List<String> {
        // Verrou levé : on demande aux moteurs de ne pas filtrer leurs résultats
        // (DuckDuckGo : kp=-2 ; Brave : safesearch=off ; Yahoo : vm=p).
        val duckOption = if (unfiltered) "&kp=-2" else ""
        val braveOption = if (unfiltered) "&safesearch=off" else ""
        val yahooOption = if (unfiltered) "&vm=p" else ""
        val encoded = query.encodeURLParameter()
        val duck = checker.fetchText("https://html.duckduckgo.com/html/?q=$encoded$duckOption")?.let(::duckDuckGoLinks).orEmpty()
        val brave = checker.fetchText("https://search.brave.com/search?q=$encoded&source=web$braveOption")?.let(::braveLinks).orEmpty()
        val yahoo = checker.fetchText("https://search.yahoo.com/search?p=$encoded&n=20$yahooOption")?.let(::yahooLinks).orEmpty()
        // On alterne les moteurs pour que le plafond ne favorise pas le premier.
        val merged = (0 until maxOf(duck.size, brave.size, yahoo.size)).flatMap { i ->
            listOfNotNull(duck.getOrNull(i), brave.getOrNull(i), yahoo.getOrNull(i))
        }
        return merged.distinct().filter { !isBlocked(hostOf(it)) }.take(MAX_RESULTS_PER_QUERY)
    }

    /** GitHub : cherche des dépôts, puis liste leurs fichiers .m3u / .m3u8 (API publique, sans compte). */
    internal suspend fun gitHubPlaylists(query: String): List<String> {
        val search = checker.fetchText(
            "https://api.github.com/search/repositories?q=${query.encodeURLParameter()}&per_page=$MAX_REPOSITORIES"
        ) ?: return emptyList()
        return repositories(search, nameKey = "full_name").take(MAX_REPOSITORIES).flatMap { (path, branch) ->
            val tree = checker.fetchText("https://api.github.com/repos/$path/git/trees/$branch?recursive=1", limit = 1_500_000)
            treePlaylists(tree.orEmpty()).take(MAX_PLAYLISTS_PER_REPOSITORY)
                .map { "https://raw.githubusercontent.com/$path/$branch/$it" }
        }
    }

    /** GitLab : même principe, sur les projets publics qui parlent d'IPTV. Interrogé une seule fois par recherche. */
    internal suspend fun gitLabPlaylists(): List<String> {
        val search = checker.fetchText(
            "https://gitlab.com/api/v4/projects?search=iptv+m3u&order_by=last_activity_at&per_page=$MAX_REPOSITORIES"
        ) ?: return emptyList()
        return repositories(search, nameKey = "path_with_namespace").take(MAX_REPOSITORIES).flatMap { (path, branch) ->
            val tree = checker.fetchText(
                "https://gitlab.com/api/v4/projects/${path.encodeURLParameter()}/repository/tree?recursive=true&per_page=100&ref=$branch"
            )
            treePlaylists(tree.orEmpty()).take(MAX_PLAYLISTS_PER_REPOSITORY)
                .map { "https://gitlab.com/$path/-/raw/$branch/$it" }
        }
    }

    /** Adresses de playlists derrière un résultat : le lien lui-même, ou les liens .m3u de la page. */
    private suspend fun playlistsBehind(result: String): List<String> {
        if (isPlaylistUrl(result)) return listOf(rawGitHub(result))
        val page = checker.fetchText(result) ?: return emptyList()
        return playlistLinks(page, result).take(MAX_PLAYLISTS_PER_PAGE)
    }

    companion object {
        private const val PARALLEL = 24
        private const val MAX_RESULTS_PER_QUERY = 12
        private const val MAX_PLAYLISTS_PER_PAGE = 10
        private const val MAX_WEB_PLAYLISTS = 60
        private const val MAX_REPOSITORIES = 6
        private const val MAX_PLAYLISTS_PER_REPOSITORY = 6

        /** Sites qui ne publient pas de playlists exploitables (réseaux sociaux, boutiques, vidéos). */
        private val blockedHosts = listOf(
            "youtube.com", "youtu.be", "facebook.com", "instagram.com", "tiktok.com", "twitter.com", "x.com",
            "pinterest.", "linkedin.com", "play.google.com", "apps.apple.com", "amazon.", "wikipedia.org",
            "duckduckgo.com", "brave.com", "yahoo.", "bing.com", "google.",
        )

        private val yahooLink = Regex("""/RU=([^/]+)/RK=""")
        private val treePath = Regex(""""path"\s*:\s*"([^"]+?\.m3u8?)"""", RegexOption.IGNORE_CASE)
        private val json = Json { ignoreUnknownKeys = true }
        private val duckLink = Regex("""uddg=([^&"']+)""")
        private val anyLink = Regex("""href="(https?://[^"]+)"""")
        private val absolutePlaylist = Regex("""https?://[^\s"'<>\\)]+?\.m3u8?(\?[^\s"'<>\\)]*)?""", RegexOption.IGNORE_CASE)
        private val relativePlaylist = Regex("""href="(/[^"]+?\.m3u8?)"""", RegexOption.IGNORE_CASE)

        /** Nom lisible d'une source web : hôte et nom du fichier. */
        fun sourceLabel(url: String): String {
            val path = url.substringBefore('?').substringAfter("://").substringAfter('/', "")
            val file = path.substringAfterLast('/').take(40)
            val parts = path.split('/')
            // Sur GitHub, c'est le dépôt qui distingue deux fichiers de même nom.
            return if (hostOf(url) == "raw.githubusercontent.com" && parts.size >= 3) {
                "GitHub ${parts[0]}/${parts[1]} · $file"
            } else if (hostOf(url) == "gitlab.com" && "/-/raw/" in path) {
                "GitLab ${path.substringBefore("/-/raw/")} · $file"
            } else {
                hostOf(url) + " · " + file
            }
        }

        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()

        fun isBlocked(host: String): Boolean = blockedHosts.any { it in host }

        fun isPlaylistUrl(url: String): Boolean =
            url.substringBefore('?').substringBefore('#').let { it.endsWith(".m3u", true) || it.endsWith(".m3u8", true) }

        /** Une page GitHub « blob » affiche le fichier dans du HTML : on vise le fichier brut. */
        fun rawGitHub(url: String): String =
            if (hostOf(url) == "github.com" && "/blob/" in url) {
                url.replace("://github.com/", "://raw.githubusercontent.com/").replace("/blob/", "/")
            } else url

        fun duckDuckGoLinks(html: String): List<String> =
            duckLink.findAll(html).mapNotNull { runCatching { it.groupValues[1].decodeURLQueryComponent() }.getOrNull() }
                .filter { it.startsWith("http") }.distinct().toList()

        fun braveLinks(html: String): List<String> =
            anyLink.findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.distinct().toList()

        /** Résultats de Yahoo : l'adresse réelle est encodée dans un lien de redirection. */
        fun yahooLinks(html: String): List<String> =
            yahooLink.findAll(html).mapNotNull { runCatching { it.groupValues[1].decodeURLQueryComponent() }.getOrNull() }
                .filter { it.startsWith("http") && !isBlocked(hostOf(it)) }.distinct().toList()

        /**
         * Dépôts d'une réponse de recherche GitHub ([nameKey] = « full_name ») ou GitLab
         * (« path_with_namespace ») : chemin du dépôt et branche par défaut.
         */
        fun repositories(response: String, nameKey: String): List<Pair<String, String>> = runCatching {
            val root = json.parseToJsonElement(response)
            // GitHub range les dépôts dans « items » ; GitLab renvoie directement la liste.
            val items = (root as? JsonObject)?.get("items")?.jsonArray ?: root.jsonArray
            items.mapNotNull { item ->
                val entry = item.jsonObject
                val path = (entry[nameKey] as? JsonPrimitive)?.contentOrNull
                val branch = (entry["default_branch"] as? JsonPrimitive)?.contentOrNull
                if (path != null && branch != null) path to branch else null
            }
        }.getOrDefault(emptyList())

        /** Fichiers .m3u / .m3u8 d'une liste de fichiers de dépôt (GitHub ou GitLab), même tronquée. */
        fun treePlaylists(tree: String): List<String> =
            treePath.findAll(tree).map { it.groupValues[1] }.distinct().toList()

        /** Liens vers des fichiers .m3u / .m3u8 présents dans une page. */
        fun playlistLinks(html: String, pageUrl: String): List<String> {
            val absolute = absolutePlaylist.findAll(html).map { it.value.replace("&amp;", "&") }
            val origin = pageUrl.substringBefore("://") + "://" + hostOf(pageUrl)
            val relative = relativePlaylist.findAll(html).map { origin + it.groupValues[1] }
            return (absolute + relative).map(::rawGitHub)
                .filter { !isBlocked(hostOf(it)) && looksLikeChannelList(it) }
                .distinct().toList()
        }

        /**
         * Un lien « .m3u » est presque toujours une liste de chaînes ; un lien « .m3u8 » est le plus
         * souvent le flux d'une seule chaîne, sauf sur GitHub ou quand son nom évoque une liste.
         */
        fun looksLikeChannelList(url: String): Boolean {
            val path = url.substringBefore('?').lowercase()
            if (path.endsWith(".m3u")) return true
            val host = hostOf(url)
            return "github" in host || listOf("playlist", "/lists/", "iptv", "channels").any { it in path }
        }

        /** Vrai pour une liste de chaînes ; faux pour le flux d'une seule chaîne ou une page HTML. */
        fun isChannelList(text: String): Boolean {
            val head = text.take(4000)
            return "#EXTINF" in text && "#EXT-X-TARGETDURATION" !in head && "#EXT-X-STREAM-INF" !in head
        }
    }
}
