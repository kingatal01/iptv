package com.yamking.iptv.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yamking.iptv.AppState
import com.yamking.iptv.PlatformBackHandler
import com.yamking.iptv.SoundSettings
import com.yamking.iptv.data.Channel

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
    // Gardés ici (et non dans les écrans) pour survivre au passage d'un écran à l'autre.
    val homeUi = remember { HomeUiState() }
    val sound = remember { SoundSettings() }
    // Liste dans laquelle on zappe + chaîne en cours de lecture.
    var queue by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var playing by remember { mutableStateOf<Channel?>(null) }

    fun play(channel: Channel) {
        playing = channel
        state.markPlayed(channel)
    }

    IptvTheme {
        // Surface fournit aussi la couleur de texte par défaut (claire sur fond sombre).
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val current = playing
            when {
                current != null -> {
                    PlatformBackHandler(enabled = true) { playing = null }
                    PlayerScreen(
                        channel = current,
                        queue = queue,
                        sound = sound,
                        isFavorite = current.url in state.favorites,
                        onToggleFavorite = { state.toggleFavorite(current) },
                        onBack = { playing = null },
                        onStep = { delta ->
                            val index = queue.indexOfFirst { it.url == current.url }
                            if (queue.isNotEmpty()) play(queue[(index + delta + queue.size) % queue.size])
                        },
                        onSelect = ::play,
                        maxHeight = state.maxHeight,
                    )
                }
                state.isEmpty && state.scan == null -> WelcomeScreen(state)
                else -> HomeScreen(state, homeUi) { channel, list ->
                    queue = list
                    play(channel)
                }
            }
        }
    }
}
