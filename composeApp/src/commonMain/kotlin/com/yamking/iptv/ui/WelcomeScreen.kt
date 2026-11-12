package com.yamking.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yamking.iptv.AppState
import com.yamking.iptv.DEFAULT_PLAYLIST_URL
import com.yamking.iptv.rememberPlaylistFilePicker

@Composable
fun WelcomeScreen(state: AppState) {
    val form = remember { BlindScanForm() }
    var searching by remember { mutableStateOf(state.blindProgress != null || state.blindResults.isNotEmpty() || state.blindSources.isNotEmpty()) }
    if (searching) BlindScanDialog(state, form, onDismiss = { searching = false })
    Box(
        Modifier.fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Palette.AccentSoft, Palette.Background),
                    radius = 1400f,
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        ImportForm(
            state = state,
            title = "Bienvenue",
            message = "Ajoutez une playlist M3U. Les chaînes sont vérifiées une à une et seules celles qui répondent sont ajoutées à votre bibliothèque.",
            onSearch = { searching = true },
        )
    }
}

/** Formulaire d'import (adresse ou fichier), utilisé à l'accueil et dans la fenêtre « Ajouter ». */
@Composable
fun ImportForm(
    state: AppState,
    title: String,
    message: String,
    onCancel: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    var url by remember { mutableStateOf(DEFAULT_PLAYLIST_URL) }
    val pickFile = rememberPlaylistFilePicker { _, content -> state.importFromText(content) }
    var choosingDefault by remember { mutableStateOf(false) }
    if (choosingDefault) DefaultListDialog(state, onDismiss = { choosingDefault = false })

    Column(
        Modifier.widthIn(max = 520.dp)
            .clip(MaterialTheme.shapes.large)
            .background(Palette.Surface)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Palette.Accent, Palette.Cyan))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Adresse de la playlist") },
            singleLine = true,
            enabled = !state.loading,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Palette.Danger, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { state.importFromUrl(url) },
            enabled = !state.loading && url.isNotBlank(),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            if (state.loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Téléchargement…")
                }
            } else {
                Text("Charger et vérifier", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = pickFile,
            enabled = !state.loading,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text("Choisir un fichier (.m3u, .tvlist)")
        }
        Spacer(Modifier.height(10.dp))
        // Liste de chaînes livrée avec l'application : l'utilisateur en importe les catégories de son choix.
        OutlinedButton(
            onClick = { choosingDefault = true },
            enabled = !state.loading,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text("Choisir dans la liste par défaut")
        }
        if (onSearch != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSearch, enabled = !state.loading) { Text("Ou rechercher des chaînes automatiquement") }
        }
        if (onCancel != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onCancel) { Text("Annuler", color = Palette.TextMuted) }
        }
    }
}