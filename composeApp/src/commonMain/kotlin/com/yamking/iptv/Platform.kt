package com.yamking.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

enum class PlayerStatus { Loading, Playing, Error }

data class TrackOption(val id: String, val label: String)

/** Volume du lecteur : conservé d'une chaîne à l'autre. */
class SoundSettings {
    var volume by mutableFloatStateOf(1f)
    var muted by mutableStateOf(false)
}

/**
 * Commandes de la chaîne en cours : le lecteur de la plateforme remplit les listes de qualités
 * et de pistes audio, l'interface écrit les choix de l'utilisateur.
 */
class PlayerController(val sound: SoundSettings) {
    var qualities by mutableStateOf<List<TrackOption>>(emptyList())
    var audios by mutableStateOf<List<TrackOption>>(emptyList())

    /** null = automatique (dans la limite de la qualité par défaut). */
    var quality by mutableStateOf<String?>(null)

    /** null = piste par défaut de la chaîne. */
    var audio by mutableStateOf<String?>(null)

    /** Ce qui est réellement lu, pour cocher la bonne ligne dans les menus. */
    var activeQuality by mutableStateOf<String?>(null)
    var activeAudio by mutableStateOf<String?>(null)

    var paused by mutableStateOf(false)
}

/**
 * Lecteur vidéo propre à chaque plateforme (Media3 sur Android, FFmpeg sur desktop).
 * [maxHeight] est la qualité par défaut (null = automatique).
 */
@Composable
expect fun VideoPlayer(
    url: String,
    modifier: Modifier,
    controller: PlayerController,
    maxHeight: Int?,
    onStatus: (PlayerStatus) -> Unit,
)

/** Plein écran : fenêtre sans bordure sur desktop, mode immersif en paysage sur Android. */
interface FullscreenControl {
    val isFullscreen: Boolean
    fun set(on: Boolean)
}

@Composable
expect fun rememberFullscreenControl(): FullscreenControl

/**
 * Signale à la plateforme qu'une vidéo est en lecture (Android : autorise l'image dans l'image
 * quand on quitte l'application). Renvoie true quand l'app est réduite en image dans l'image.
 */
@Composable
expect fun playbackSession(playing: Boolean): Boolean

/** true sur ordinateur (clavier et souris), false sur téléphone, tablette et télé. */
expect val isDesktop: Boolean

expect fun nowMillis(): Long

/** Empreinte SHA-256 de [text], en hexadécimal (sert à ne jamais enregistrer un code en clair). */
expect fun sha256Hex(text: String): String

/** Nom français d'un pays à partir de son code ISO à deux lettres. */
expect fun countryName(code: String): String

/** Renvoie une action qui ouvre le sélecteur de fichier et fournit (nom, contenu). */
@Composable
expect fun rememberPlaylistFilePicker(onPicked: (name: String, content: String) -> Unit): () -> Unit

/** Renvoie une action qui demande où enregistrer un fichier, puis y écrit le contenu. */
@Composable
expect fun rememberPlaylistFileSaver(): (fileName: String, content: String) -> Unit

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Petit stockage de fichiers texte dans le dossier de données de l'application. */
expect object Storage {
    fun read(name: String): String?
    fun write(name: String, content: String)
}
