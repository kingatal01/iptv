package com.yamking.iptv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.roundToInt
@Composable
actual fun VideoPlayer(
    url: String,
    modifier: Modifier,
    controller: PlayerController,
    maxHeight: Int?,
    onStatus: (PlayerStatus) -> Unit,
) {
    val report by rememberUpdatedState(onStatus)
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var tick by remember(url) { mutableIntStateOf(0) }
    var lock by remember(url) { mutableStateOf(Any()) }
    var current by remember(url) { mutableStateOf<FfmpegSession?>(null) }
    val quality = controller.quality
    val audio = controller.audio

    // Changer de qualité ou de piste audio relance la lecture avec le nouveau choix.
    DisposableEffect(url, quality, audio, maxHeight) {
        report(PlayerStatus.Loading)
        val session = FfmpegSession(
            url = url,
            quality = quality,
            audio = audio,
            maxHeight = maxHeight,
            onTracks = { qualities, audios, activeQuality, activeAudio ->
                controller.qualities = qualities
                controller.audios = audios
                controller.activeQuality = activeQuality
                controller.activeAudio = activeAudio
            },
            onReady = { image = it.asComposeImageBitmap() },
            onFrame = { tick++ },
            onStatus = { report(it) },
        )
        lock = session.frameLock
        current = session
        session.start()
        onDispose { session.stop() }
    }
    // Volume et pause s'appliquent à la session en cours sans relancer la lecture.
    val gain = if (controller.sound.muted) 0f else controller.sound.volume
    val paused = controller.paused
    SideEffect {
        current?.gain = gain
        current?.paused = paused
    }

    Canvas(modifier.background(Color.Black)) {
        tick // relit l'état : redessine à chaque nouvelle image décodée
        val bitmap = image ?: return@Canvas
        val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
        val target = IntSize((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt())
        // Skia copie les pixels pendant cet appel : le décodeur ne doit pas écrire en même temps.
        synchronized(lock) { drawImage(
            image = bitmap,
            dstOffset = IntOffset(((size.width - target.width) / 2).roundToInt(), ((size.height - target.height) / 2).roundToInt()),
            dstSize = target,
        ) }
    }
}

@Composable
actual fun rememberPlaylistFilePicker(onPicked: (name: String, content: String) -> Unit): () -> Unit = {
    val dialog = FileDialog(null as Frame?, "Choisir une playlist", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".m3u", true) || name.endsWith(".m3u8", true) || name.endsWith(".json", true) || name.endsWith(".tvlist", true) }
    dialog.isVisible = true
    val directory = dialog.directory
    val file = dialog.file
    if (directory != null && file != null) {
        runCatching { File(directory, file).readText() }.onSuccess { onPicked(file, it) }
    }
}

@Composable
actual fun rememberPlaylistFileSaver(): (fileName: String, content: String) -> Unit = { fileName, content ->
    val dialog = FileDialog(null as Frame?, "Enregistrer", FileDialog.SAVE)
    dialog.file = fileName
    dialog.isVisible = true
    val directory = dialog.directory
    val file = dialog.file
    if (directory != null && file != null) {
        val name = if (file.contains('.')) file else file + "." + fileName.substringAfterLast('.', "m3u")
        runCatching { File(directory, name).writeText(content) }
    }
}

/** État de la fenêtre principale, fourni par Main.kt pour le plein écran. */
val LocalAppWindowState = compositionLocalOf<WindowState?> { null }

@Composable
actual fun rememberFullscreenControl(): FullscreenControl {
    val window = LocalAppWindowState.current
    return remember(window) {
        object : FullscreenControl {
            private var previous = WindowPlacement.Floating
            override val isFullscreen: Boolean get() = window?.placement == WindowPlacement.Fullscreen
            override fun set(on: Boolean) {
                val state = window ?: return
                if (on == isFullscreen) return
                if (on) previous = state.placement
                state.placement = if (on) WindowPlacement.Fullscreen else previous
            }
        }
    }
}

@Composable
actual fun playbackSession(playing: Boolean): Boolean = false

actual val isDesktop: Boolean = true

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun sha256Hex(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

actual fun countryName(code: String): String =
    Locale("", code).getDisplayCountry(Locale.FRENCH).ifBlank { code }

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

actual object Storage {
    private fun dir(): File = (System.getProperty("iptv.data.dir")?.let(::File)
        ?: File(System.getProperty("user.home"), ".iptv-player")).apply { mkdirs() }

    actual fun read(name: String): String? = File(dir(), name).takeIf { it.exists() }?.readText()

    actual fun write(name: String, content: String) = File(dir(), name).writeText(content)
}
