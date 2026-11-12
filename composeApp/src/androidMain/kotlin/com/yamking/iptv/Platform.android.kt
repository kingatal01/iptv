package com.yamking.iptv

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import java.util.Locale
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.File

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(
    url: String,
    modifier: Modifier,
    controller: PlayerController,
    maxHeight: Int?,
    onStatus: (PlayerStatus) -> Unit,
) {
    val context = LocalContext.current
    val report by rememberUpdatedState(onStatus)
    // Groupes de pistes audio du flux en cours, par identifiant d'option.
    val audioGroups = remember(url) { mutableMapOf<String, TrackGroup>() }

    val player = remember(url) {
        // Les playlists IPTV redirigent souvent de http vers https (et inversement).
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android) IPTVPlayer")
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> report(PlayerStatus.Loading)
                            Player.STATE_READY -> report(PlayerStatus.Playing)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) = report(PlayerStatus.Error)

                    // Le flux annonce ses qualités et ses pistes audio : on les expose aux menus du lecteur.
                    override fun onTracksChanged(tracks: Tracks) {
                        val heights = sortedSetOf<Int>(reverseOrder())
                        val audios = mutableListOf<TrackOption>()
                        var activeAudio: String? = null
                        audioGroups.clear()
                        tracks.groups.forEachIndexed { index, group ->
                            when (group.type) {
                                C.TRACK_TYPE_VIDEO -> for (i in 0 until group.length) {
                                    val height = group.getTrackFormat(i).height
                                    if (group.isTrackSupported(i) && height > 0) heights += height
                                }
                                C.TRACK_TYPE_AUDIO -> if (group.length > 0 && group.isTrackSupported(0)) {
                                    val format = group.getTrackFormat(0)
                                    val language = format.language?.takeIf { it != "und" }
                                        ?.let { Locale.forLanguageTag(it).displayLanguage }
                                        ?.replaceFirstChar { it.uppercase() }
                                    val label = listOfNotNull(format.label?.takeIf { it.isNotBlank() }, language)
                                        .distinct().joinToString(" · ").ifEmpty { "Piste ${audios.size + 1}" }
                                    val id = "a$index"
                                    audioGroups[id] = group.mediaTrackGroup
                                    audios += TrackOption(id, label)
                                    if (group.isSelected) activeAudio = id
                                }
                            }
                        }
                        controller.qualities = heights.map { TrackOption(it.toString(), "${it}p") }
                        controller.audios = audios
                        controller.activeAudio = activeAudio
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.height > 0) controller.activeQuality = videoSize.height.toString()
                    }
                })
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
    }
    // Quand l'app passe en arrière-plan (hors image dans l'image), la lecture s'arrête ; elle reprend au retour.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.playWhenReady = false
                Lifecycle.Event.ON_START -> player.playWhenReady = !controller.paused
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    val volume = if (controller.sound.muted) 0f else controller.sound.volume
    val paused = controller.paused
    LaunchedEffect(player, volume, paused) {
        player.volume = volume
        player.playWhenReady = !paused
    }

    // Qualité : le choix de l'utilisateur, sinon le plafond global, sinon adaptation libre au débit.
    val quality = controller.quality
    LaunchedEffect(player, quality, maxHeight) {
        val cap = quality?.toIntOrNull() ?: maxHeight
        val builder = player.trackSelectionParameters.buildUpon()
        if (cap != null) builder.setMaxVideoSize(Int.MAX_VALUE, cap) else builder.clearVideoSizeConstraints()
        // Une qualité choisie à la main est tenue fixe au lieu de rester un simple maximum.
        if (quality != null && cap != null) builder.setMinVideoSize(0, cap) else builder.setMinVideoSize(0, 0)
        player.trackSelectionParameters = builder.build()
    }

    val audio = controller.audio
    LaunchedEffect(player, audio) {
        val group = audio?.let { audioGroups[it] } ?: return@LaunchedEffect
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group, 0))
            .build()
    }

    AndroidView(
        modifier = modifier,
        factory = {
            // Les commandes sont celles de l'application (communes au desktop), pas celles de Media3.
            PlayerView(it).apply {
                keepScreenOn = true
                useController = false
            }
        },
        update = { it.player = player },
    )
}

@Composable
actual fun rememberPlaylistFilePicker(onPicked: (name: String, content: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                } ?: "Playlist"
                val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                onPicked(name, text)
            }
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

@Composable
actual fun rememberPlaylistFileSaver(): (fileName: String, content: String) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { it.write(pending.value.encodeToByteArray()) }
            }
        }
    }
    return { fileName, content ->
        pending.value = content
        launcher.launch(fileName)
    }
}

/** Partagé avec MainActivity pour l'image dans l'image. */
object PlaybackState {
    @Volatile
    var playing = false
    val inPictureInPicture = mutableStateOf(false)
}

@Composable
actual fun playbackSession(playing: Boolean): Boolean {
    DisposableEffect(playing) {
        PlaybackState.playing = playing
        onDispose { PlaybackState.playing = false }
    }
    return PlaybackState.inPictureInPicture.value
}

@Composable
actual fun rememberFullscreenControl(): FullscreenControl {
    val activity = LocalActivity.current
    val active = remember { mutableStateOf(false) }
    // En quittant le lecteur, on rend les barres système et l'orientation libre.
    DisposableEffect(activity) { onDispose { applyFullscreen(activity, false) } }
    return remember(activity) {
        object : FullscreenControl {
            override val isFullscreen: Boolean get() = active.value
            override fun set(on: Boolean) {
                active.value = on
                applyFullscreen(activity, on)
            }
        }
    }
}

private fun applyFullscreen(activity: Activity?, on: Boolean) {
    activity ?: return
    val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (on) {
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        bars.show(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

actual val isDesktop: Boolean = false

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun sha256Hex(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

actual fun countryName(code: String): String =
    Locale("", code).getDisplayCountry(Locale.FRENCH).ifBlank { code }

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

actual object Storage {
    private var dir: File? = null

    fun init(context: Context) {
        dir = context.filesDir
    }

    actual fun read(name: String): String? = dir?.let { File(it, name) }?.takeIf { it.exists() }?.readText()

    actual fun write(name: String, content: String) {
        dir?.let { File(it, name).writeText(content) }
    }
}
