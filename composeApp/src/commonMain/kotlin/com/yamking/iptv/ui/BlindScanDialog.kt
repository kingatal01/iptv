package com.yamking.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yamking.iptv.AppState
import com.yamking.iptv.countryName
import com.yamking.iptv.data.AdultFilter
import com.yamking.iptv.data.BlindScanRequest
import com.yamking.iptv.data.ScanLanguages

private const val MAX_TARGET = 2000

/** Réglages de la recherche, conservés par l'écran pour retrouver ses choix à la recherche suivante. */
class BlindScanForm {
    var language by mutableStateOf(ScanLanguages.all.first())
    var target by mutableStateOf("50")
    var publicIndexes by mutableStateOf(true)
    var webSearch by mutableStateOf(false)
    var keywords by mutableStateOf("")
    var includeUnknown by mutableStateOf(false)

    /** false = recherche rapide, true = recherche lente (N chaînes par source, tri strict). */
    var thorough by mutableStateOf(false)
}

/**
 * Recherche automatique de chaînes en trois temps : réglages, progression, puis résultats à cocher.
 * Rien n'entre dans la bibliothèque sans validation de l'utilisateur.
 */
@Composable
fun BlindScanDialog(state: AppState, form: BlindScanForm, onDismiss: () -> Unit) {
    val running = state.blindProgress != null
    // Pendant la recherche, la fenêtre ne se ferme que par « Arrêter ».
    Dialog(onDismissRequest = { if (!running) onDismiss() }) {
        Column(
            Modifier.widthIn(max = 520.dp)
                .clip(MaterialTheme.shapes.large)
                .background(Palette.Surface)
                .padding(top = 24.dp, bottom = 16.dp),
        ) {
            when {
                running -> Progress(state)
                state.blindSources.isNotEmpty() -> Sources(state, onDismiss)
                state.blindResults.isNotEmpty() -> Results(state, onDismiss)
                state.blindFinished -> Empty(state, onDismiss)
                else -> Setup(state, form, onDismiss)
            }
        }
    }
}

@Composable
private fun Setup(state: AppState, form: BlindScanForm, onDismiss: () -> Unit) {
    val target = form.target.toIntOrNull() ?: 0
    // Mots-clés pour adultes : la recherche n'est lancée qu'après saisie du code.
    val adultKeywords = AdultFilter.hasAdultKeyword(form.keywords.split(','))
    val lockedKeywords = adultKeywords && !state.adultUnlocked
    var askCode by remember { mutableStateOf(false) }
    if (askCode) AdultLockDialog(state, onDismiss = { askCode = false })
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Rechercher des chaînes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "L'application parcourt des listes de chaînes, teste chaque flux et s'arrête dès que le nombre demandé est atteint. Vous choisissez ensuite lesquelles ajouter.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextMuted,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    var open by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = form.language.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Langue") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Le champ sert d'affichage ; le bouton transparent posé dessus ouvre la liste.
                    TextButton(onClick = { open = true }, modifier = Modifier.matchParentSize()) {}
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        ScanLanguages.all.forEach { language ->
                            DropdownMenuItem(
                                text = { Text(language.label, color = if (language == form.language) Palette.Accent else Palette.Text) },
                                onClick = {
                                    form.language = language
                                    open = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = form.target,
                    onValueChange = { text -> form.target = text.filter(Char::isDigit).take(4) },
                    label = { Text(if (form.thorough) "Nombre par source" else "Nombre à trouver") },
                    singleLine = true,
                    isError = target !in 1..MAX_TARGET,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.width(170.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "TYPE DE RECHERCHE",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextMuted,
            modifier = Modifier.padding(start = 24.dp, bottom = 2.dp),
        )
        ModeRow(
            title = "Recherche rapide",
            detail = "S'arrête dès que le nombre demandé est trouvé, toutes sources confondues.",
            selected = !form.thorough,
        ) { form.thorough = false }
        ModeRow(
            title = "Recherche lente",
            detail = "Recense les sources, vous laisse choisir lesquelles fouiller, puis cherche ce nombre de chaînes dans chacune. Ne garde que les chaînes qui correspondent vraiment à la langue et aux mots-clés.",
            selected = form.thorough,
        ) { form.thorough = true }
        Spacer(Modifier.height(10.dp))
        Text(
            "SOURCES",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextMuted,
            modifier = Modifier.padding(start = 24.dp, bottom = 2.dp),
        )
        CheckRow("Index publics de chaînes gratuites (iptv-org, Free-TV)", form.publicIndexes) {
            form.publicIndexes = !form.publicIndexes
        }
        CheckRow("Recherche web (DuckDuckGo, Brave, Yahoo, GitHub, GitLab)", form.webSearch) { form.webSearch = !form.webSearch }
        if (form.webSearch || form.thorough) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.keywords,
                    onValueChange = { form.keywords = it },
                    label = { Text("Mots-clés, séparés par des virgules") },
                    placeholder = { Text("sport, cinéma, info…") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (adultKeywords) {
                    // Le code n'est demandé qu'ici, au moment où une recherche vise du contenu pour adultes.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.adultUnlocked) "Recherche de contenu pour adultes déverrouillée pour cette session."
                            else "Ces mots-clés visent du contenu pour adultes : un code est nécessaire pour lancer cette recherche.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.adultUnlocked) Palette.Cyan else Palette.Danger,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { if (state.adultUnlocked) state.lockAdult() else askCode = true }) {
                            Text(
                                when {
                                    state.adultUnlocked -> "Verrouiller"
                                    state.hasAdultPin -> "Saisir le code"
                                    else -> "Définir un code"
                                }
                            )
                        }
                    }
                }
                if (form.thorough) {
                    Text(
                        "En recherche lente, seules les chaînes dont le nom ou la catégorie contient un de ces mots sont gardées. Laissez vide pour garder toute la langue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextMuted,
                    )
                }
                if (form.webSearch) {
                    Text(
                        "La recherche web peut ramener des listes de chaînes payantes diffusées sans autorisation. N'ajoutez que des chaînes que vous avez le droit de regarder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Danger,
                    )
                }
            }
        }
        // En recherche lente le tri est strict : les chaînes sans langue ni pays connus sont toujours écartées.
        if (form.webSearch && !form.thorough) {
            CheckRow("Inclure les chaînes sans langue ni pays connus", form.includeUnknown) {
                form.includeUnknown = !form.includeUnknown
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Annuler", color = Palette.TextMuted) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    state.startBlindScan(
                        BlindScanRequest(
                            language = form.language,
                            target = target,
                            usePublicIndexes = form.publicIndexes,
                            useWebSearch = form.webSearch,
                            keywords = form.keywords.split(','),
                            includeUnknownLanguage = form.includeUnknown,
                            thorough = form.thorough,
                        )
                    )
                },
                enabled = target in 1..MAX_TARGET && (form.publicIndexes || form.webSearch) && !lockedKeywords,
                shape = MaterialTheme.shapes.medium,
            ) { Text("Lancer la recherche") }
        }
    }
}

@Composable
private fun Progress(state: AppState) {
    val progress = state.blindProgress ?: return
    val target = state.blindTarget.coerceAtLeast(1)
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (state.blindDiscovering) "Recensement des sources…" else "Recherche en cours…",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (state.blindDiscovering) {
            // Recherche lente, étape 1 : on recense les sources avant de laisser l'utilisateur choisir.
            val total = progress.sourcesTotal
            Text(
                if (total == 0) "Recherche des sources…" else "${progress.found} sources utilisables",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Accent,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else (progress.sources.toFloat() / total).coerceIn(0f, 1f) },
                color = Palette.Accent,
                trackColor = Palette.SurfaceHigh,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
            Text(
                if (total == 0) "Interrogation des index et des moteurs de recherche."
                else "Lecture de la liste ${progress.sources + 1} / $total",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextMuted,
            )
        } else if (state.blindThorough) {
            // Recherche lente : l'avancement se mesure en sources parcourues, pas en chaînes.
            val total = progress.sourcesTotal
            Text(
                if (progress.found == 1) "1 chaîne trouvée" else "${progress.found} chaînes trouvées",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Accent,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else (progress.sources.toFloat() / total).coerceIn(0f, 1f) },
                color = Palette.Accent,
                trackColor = Palette.SurfaceHigh,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
            Text(
                if (total == 0) "Recensement des sources…"
                else "Source ${progress.sources} / $total · jusqu'à $target chaînes par source · ${progress.tested} testées",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextMuted,
            )
        } else {
            Text(
                "${progress.found} / $target chaînes trouvées",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Accent,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(
                progress = { (progress.found.toFloat() / target).coerceIn(0f, 1f) },
                color = Palette.Accent,
                trackColor = Palette.SurfaceHigh,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
            Text(
                "${progress.tested} chaînes testées · ${progress.sources} listes parcourues",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextMuted,
            )
        }
        Text(
            if (progress.stage.isEmpty()) " " else "Source : ${progress.stage}",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextMuted,
            maxLines = 1,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = state::stopBlindScan, shape = MaterialTheme.shapes.medium) { Text("Arrêter") }
        }
    }
}

/** Recherche lente : l'utilisateur coche les sources à fouiller. */
@Composable
private fun ColumnScope.Sources(state: AppState, onDismiss: () -> Unit) {
    val sources = state.blindSources
    var excluded by remember { mutableStateOf(emptySet<String>()) }
    val chosen = sources.filter { it.url !in excluded }
    val allSelected = excluded.isEmpty()

    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(
            if (sources.size == 1) "1 source trouvée" else "${sources.size} sources trouvées",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Cochez les sources à fouiller. Dans chacune, la recherche s'arrête à ${state.blindTarget} chaînes valides. Le nombre indiqué est celui des chaînes qui correspondent à vos critères, avant test.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextMuted,
        )
    }
    Spacer(Modifier.height(6.dp))
    CheckRow(if (allSelected) "Tout désélectionner" else "Tout sélectionner", allSelected, emphasized = true) {
        excluded = if (allSelected) sources.map { it.url }.toSet() else emptySet()
    }
    LazyColumn(Modifier.weight(1f, fill = false)) {
        items(sources, key = { it.url }) { source ->
            CheckRow(label = source.label, checked = source.url !in excluded, count = source.channels.size) {
                excluded = if (source.url in excluded) excluded - source.url else excluded + source.url
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = {
            state.closeBlindScan()
            onDismiss()
        }) { Text("Annuler", color = Palette.TextMuted) }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { state.scanBlindSources(chosen) },
            enabled = chosen.isNotEmpty(),
            shape = MaterialTheme.shapes.medium,
        ) { Text(if (chosen.size == 1) "Fouiller 1 source" else "Fouiller ${chosen.size} sources") }
    }
}

@Composable
private fun ColumnScope.Results(state: AppState, onDismiss: () -> Unit) {
    val results = state.blindResults
    var excluded by remember { mutableStateOf(emptySet<String>()) }
    val chosen = results.filter { it.url !in excluded }
    val allSelected = excluded.isEmpty()

    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(
            if (results.size == 1) "1 chaîne trouvée" else "${results.size} chaînes trouvées",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (state.blindThorough) "Recherche lente : jusqu'à ${state.blindTarget} chaînes par source. Décochez celles que vous ne voulez pas."
            else if (results.size < state.blindTarget) "Moins que les ${state.blindTarget} demandées : les sources sont épuisées ou la recherche a été arrêtée."
            else "Chaque flux a répondu au test. Décochez celles que vous ne voulez pas.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextMuted,
        )
    }
    Spacer(Modifier.height(6.dp))
    CheckRow(if (allSelected) "Tout désélectionner" else "Tout sélectionner", allSelected, emphasized = true) {
        excluded = if (allSelected) results.map { it.url }.toSet() else emptySet()
    }
    LazyColumn(Modifier.weight(1f, fill = false)) {
        items(results, key = { it.url }) { channel ->
            CheckRow(
                label = channel.name,
                checked = channel.url !in excluded,
                detail = listOfNotNull(channel.country?.let(::countryName), channel.quality).joinToString(" · "),
            ) {
                excluded = if (channel.url in excluded) excluded - channel.url else excluded + channel.url
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = {
            state.closeBlindScan()
            onDismiss()
        }) { Text("Ne rien ajouter", color = Palette.TextMuted) }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                state.addBlindResults(chosen)
                onDismiss()
            },
            enabled = chosen.isNotEmpty(),
            shape = MaterialTheme.shapes.medium,
        ) { Text(if (chosen.size == 1) "Ajouter 1 chaîne" else "Ajouter ${chosen.size} chaînes") }
    }
}

@Composable
private fun Empty(state: AppState, onDismiss: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Aucune chaîne trouvée", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Aucun flux valide dans cette langue n'a été trouvé, ou toutes les chaînes trouvées sont déjà dans votre bibliothèque. Essayez une autre source ou d'autres mots-clés.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextMuted,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                state.closeBlindScan()
                onDismiss()
            }) { Text("Fermer", color = Palette.TextMuted) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = state::closeBlindScan, shape = MaterialTheme.shapes.medium) { Text("Nouvelle recherche") }
        }
    }
}

/** Choix exclusif (bouton radio) avec une ligne d'explication ; toute la ligne est cliquable. */
@Composable
private fun ModeRow(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .focusHighlight(MaterialTheme.shapes.small)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 24.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
    }
}