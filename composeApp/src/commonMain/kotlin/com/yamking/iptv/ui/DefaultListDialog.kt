package com.yamking.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.yamking.iptv.data.TvList
import com.yamking.iptv.data.TvListFormat
import com.yamking.iptv.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Liste de chaînes livrée avec l'application (fichier « files/default.tvlist » des ressources). */
suspend fun loadDefaultTvList(): TvList? = withContext(Dispatchers.Default) {
    runCatching { TvListFormat.read(Res.readBytes("files/default.tvlist").decodeToString()) }.getOrNull()
}

/**
 * Propose la liste par défaut, catégorie par catégorie. Les chaînes des catégories cochées sont
 * testées puis ajoutées à la bibliothèque ; rien n'est coché d'office.
 */
@Composable
fun DefaultListDialog(state: AppState, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf<TvList?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        list = loadDefaultTvList()
        failed = list == null
    }
    val loaded = list
    val categories = remember(loaded) { loaded?.let(TvListFormat::categories).orEmpty() }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // Chaînes des catégories cochées qui ne sont pas déjà dans la bibliothèque.
    val known = remember(state.library) { state.library.mapTo(HashSet()) { it.url } }
    val count = remember(loaded, selected, known) {
        loaded?.let { TvListFormat.select(it.channels, selected, emptySet(), false).count { c -> c.url !in known } } ?: 0
    }
    val allSelected = categories.isNotEmpty() && selected.size == categories.size

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 460.dp)
                .clip(MaterialTheme.shapes.large)
                .background(Palette.Surface)
                .padding(top = 24.dp, bottom = 16.dp),
        ) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Liste par défaut", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        failed -> "La liste par défaut n'a pas pu être lue."
                        loaded == null -> "Lecture de la liste…"
                        else -> "${loaded.channels.size} chaînes livrées avec l'application. Cochez les catégories à importer : chaque chaîne est testée, seules celles qui répondent sont ajoutées."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) Palette.Danger else Palette.TextMuted,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (loaded == null && !failed) {
                CircularProgressIndicator(
                    color = Palette.Accent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.padding(24.dp).size(28.dp).align(Alignment.CenterHorizontally),
                )
            }
            if (categories.isNotEmpty()) {
                CheckRow(if (allSelected) "Tout désélectionner" else "Tout sélectionner", allSelected, emphasized = true) {
                    selected = if (allSelected) emptySet() else categories.map { it.first }.toSet()
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(categories) { (name, size) ->
                        CheckRow(label = name, checked = name in selected, count = size) {
                            selected = if (name in selected) selected - name else selected + name
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        selected.isEmpty() -> "Aucune catégorie cochée"
                        count == 0 -> "Déjà dans votre bibliothèque"
                        else -> "$count chaînes à tester"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Annuler", color = Palette.TextMuted) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        loaded?.let { state.importTvList(it, selected) }
                        onDismiss()
                    },
                    enabled = count > 0,
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Importer") }
            }
        }
    }
}
