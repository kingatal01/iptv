package com.yamking.iptv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yamking.iptv.PlayerController
import com.yamking.iptv.PlayerStatus
import com.yamking.iptv.SoundSettings
import com.yamking.iptv.VideoPlayer
import com.yamking.iptv.data.Channel
import com.yamking.iptv.isDesktop
import com.yamking.iptv.playbackSession
import com.yamking.iptv.rememberFullscreenControl
import kotlinx.coroutines.delay

private const val MAX_RETRIES = 3
private const val HIDE_CONTROLS_AFTER_MS = 4_000L

@Composable
fun PlayerScreen(
    channel: Channel,
    queue: List<Channel>,
    sound: SoundSettings,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    onStep: (Int) -> Unit,
    onSelect: (Channel) -> Unit,
    maxHeight: Int? = null,
) {
    var status by remember(channel.url) { mutableStateOf(PlayerStatus.Loading) }
    // Qualités et pistes audio propres à la chaîne en cours : on repart de zéro à chaque zapping.
    val controller = remember(channel.url) { PlayerController(sound) }
    // Reconnexion automatique : [attempt] compte les essais, [generation] relance le lecteur.
    var attempt by remember(channel.url) { mutableIntStateOf(0) }
    var generation by remember(channel.url) { mutableIntStateOf(0) }
    val failed = status == PlayerStatus.Error && attempt >= MAX_RETRIES

    val fullscreen = rememberFullscreenControl()
    val inPictureInPicture = playbackSession(playing = status == PlayerStatus.Playing && !controller.paused)

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    LaunchedEffect(channel.url, status, attempt) {
        if (status == PlayerStatus.Error && attempt < MAX_RETRIES) {
            // Coupure : on retente avant d'annoncer une panne, avec une attente croissante.
            delay(1_500L * (attempt + 1))
            attempt++
            status = PlayerStatus.Loading
            generation++
        } else if (status == PlayerStatus.Playing && attempt > 0) {
            delay(15_000)
            attempt = 0 // la lecture tient : on repart avec tous les essais
        }
    }

    // Les commandes s'effacent après quelques secondes de lecture sans interaction.
    LaunchedEffect(interaction, menuOpen, listOpen, status, controller.paused) {
        controlsVisible = true
        if (status == PlayerStatus.Playing && !menuOpen && !listOpen && !controller.paused) {
            delay(HIDE_CONTROLS_AFTER_MS)
            controlsVisible = false
        }
    }

    fun toggleFullscreen() = fullscreen.set(!fullscreen.isFullscreen)

    fun changeVolume(delta: Float) {
        sound.volume = (sound.volume + delta).coerceIn(0f, 1f)
        sound.muted = false
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // Sur télé, les flèches servent à atteindre les boutons quand les commandes sont affichées.
        val arrowsZap = isDesktop || !controlsVisible
        val handled = when (event.key) {
            Key.Escape -> {
                if (fullscreen.isFullscreen) fullscreen.set(false) else onBack()
                true
            }
            Key.Spacebar, Key.MediaPlayPause -> { controller.paused = !controller.paused; true }
            Key.F -> { toggleFullscreen(); true }
            Key.M -> { sound.muted = !sound.muted; true }
            Key.L -> { listOpen = !listOpen; true }
            Key.ChannelUp, Key.PageUp, Key.MediaNext -> { onStep(1); true }
            Key.ChannelDown, Key.PageDown, Key.MediaPrevious -> { onStep(-1); true }
            Key.DirectionRight -> if (arrowsZap) { onStep(1); true } else false
            Key.DirectionLeft -> if (arrowsZap) { onStep(-1); true } else false
            Key.DirectionUp -> if (isDesktop) { changeVolume(0.1f); true } else if (arrowsZap) { onStep(1); true } else false
            Key.DirectionDown -> if (isDesktop) { changeVolume(-0.1f); true } else if (arrowsZap) { onStep(-1); true } else false
            Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> if (!controlsVisible) { interaction++; true } else false
            else -> false
        }
        if (handled) interaction++
        return handled
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent(::onKey)
            .pointerInput(Unit) {
                if (isDesktop) {
                    // Souris : le moindre mouvement réaffiche les commandes.
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            interaction++
                        }
                    }
                } else {
                    // Tactile : un appui sur la vidéo affiche ou masque les commandes.
                    detectTapGestures {
                        if (controlsVisible && status == PlayerStatus.Playing) controlsVisible = false else interaction++
                    }
                }
            },
    ) {
        val wide = maxWidth >= 600.dp

        if (!failed) {
            key(channel.url, generation) {
                VideoPlayer(
                    url = channel.url,
                    modifier = Modifier.fillMaxSize(),
                    controller = controller,
                    maxHeight = maxHeight,
                    onStatus = { status = it },
                )
            }
        }
        // Réduite en vignette flottante, l'app ne montre que la vidéo.
        if (inPictureInPicture) return@BoxWithConstraints

        when {
            failed -> ErrorPanel(
                onRetry = {
                    attempt = 0
                    status = PlayerStatus.Loading
                    generation++
                },
                onNext = { onStep(1) },
                modifier = Modifier.align(Alignment.Center),
            )
            status != PlayerStatus.Playing -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = Palette.Accent, strokeWidth = 3.dp)
                if (attempt > 0) {
                    Text("Reconnexion… ($attempt/$MAX_RETRIES)", color = Palette.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible || failed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xD9000000), Color.Transparent)))
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (fullscreen.isFullscreen) fullscreen.set(false) else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White)
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        channel.name,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (channel.groups + listOfNotNull(channel.quality)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xCCFFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                        tint = if (isFavorite) Palette.Heart else Color.White,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !failed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                    .padding(start = 4.dp, end = 4.dp, top = 28.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Chaîne précédente") { onStep(-1) }
                ControlButton(
                    if (controller.paused) Icons.Filled.PlayArrow else PlayerIcons.Pause,
                    if (controller.paused) "Reprendre" else "Pause",
                ) { controller.paused = !controller.paused }
                ControlButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Chaîne suivante") { onStep(1) }

                val silent = sound.muted || sound.volume == 0f
                ControlButton(
                    if (silent) PlayerIcons.VolumeOff else PlayerIcons.VolumeUp,
                    if (silent) "Rétablir le son" else "Couper le son",
                ) { sound.muted = !sound.muted }
                if (wide) {
                    Slider(
                        value = if (sound.muted) 0f else sound.volume,
                        onValueChange = {
                            sound.volume = it
                            sound.muted = false
                        },
                        modifier = Modifier.width(130.dp),
                    )
                }
                Spacer(Modifier.weight(1f))

                if (controller.qualities.size > 1 || controller.audios.size > 1) {
                    Box {
                        ControlButton(Icons.Filled.Settings, "Qualité et audio") { menuOpen = true }
                        TrackMenu(controller, menuOpen) { menuOpen = false }
                    }
                }
                ControlButton(Icons.AutoMirrored.Filled.List, "Liste des chaînes") { listOpen = !listOpen }
                ControlButton(
                    if (fullscreen.isFullscreen) PlayerIcons.FullscreenExit else PlayerIcons.Fullscreen,
                    if (fullscreen.isFullscreen) "Quitter le plein écran" else "Plein écran",
                ) { toggleFullscreen() }
            }
        }

        AnimatedVisibility(
            visible = listOpen,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            ChannelListPanel(
                queue = queue,
                current = channel,
                onSelect = onSelect,
                onClose = { listOpen = false },
            )
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun TrackMenu(controller: PlayerController, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (controller.qualities.size > 1) {
            MenuHeader("Qualité")
            MenuChoice("Automatique", selected = controller.quality == null) {
                controller.quality = null
                onDismiss()
            }
            controller.qualities.forEach { option ->
                // En automatique, on indique seulement la qualité en cours de lecture.
                val playing = controller.quality == null && option.id == controller.activeQuality
                MenuChoice(
                    if (playing) "${option.label}  (en cours)" else option.label,
                    selected = controller.quality == option.id,
                ) {
                    controller.quality = option.id
                    onDismiss()
                }
            }
        }
        if (controller.audios.size > 1) {
            MenuHeader("Piste audio")
            controller.audios.forEach { option ->
                MenuChoice(option.label, selected = option.id == (controller.audio ?: controller.activeAudio)) {
                    controller.audio = option.id
                    onDismiss()
                }
            }
        }
    }
}

/** Liste des chaînes de la sélection en cours, pour zapper sans quitter le lecteur. */
@Composable
private fun ChannelListPanel(
    queue: List<Channel>,
    current: Channel,
    onSelect: (Channel) -> Unit,
    onClose: () -> Unit,
) {
    val index = queue.indexOfFirst { it.url == current.url }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (index - 3).coerceAtLeast(0))
    Column(Modifier.width(300.dp).fillMaxHeight().background(Color(0xF2141824))) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Chaînes (${queue.size})",
                fontWeight = FontWeight.SemiBold,
                color = Palette.Text,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Fermer la liste", tint = Palette.TextMuted) }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            items(queue, key = { it.url }) { item ->
                val active = item.url == current.url
                Row(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 1.dp)
                        .background(
                            if (active) Palette.AccentSoft else Color.Transparent,
                            MaterialTheme.shapes.small,
                        )
                        .focusHighlight(MaterialTheme.shapes.small)
                        .clickable { onSelect(item) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.name,
                        color = if (active) Palette.Text else Palette.TextMuted,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    item.quality?.let {
                        Text(it, color = Palette.Cyan, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(onRetry: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Cette chaîne ne répond pas", fontWeight = FontWeight.SemiBold, color = Palette.Text)
        Text(
            "Le flux est peut-être hors ligne ou indisponible dans votre région.",
            color = Palette.TextMuted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRetry, shape = MaterialTheme.shapes.medium) { Text("Réessayer") }
            Button(onClick = onNext, shape = MaterialTheme.shapes.medium) { Text("Chaîne suivante") }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextMuted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun MenuChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (selected) Palette.Accent else Palette.Text,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        trailingIcon = { if (selected) Icon(Icons.Filled.Check, null, tint = Palette.Accent) },
        onClick = onClick,
    )
}
