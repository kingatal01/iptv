package com.yamking.iptv

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import com.yamking.iptv.data.HlsMaster
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

private const val MAX_WIDTH = 1280
private const val AUTO_MAX_HEIGHT = 720
private val http: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.ALWAYS)
    .connectTimeout(Duration.ofSeconds(8))
    .build()
private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) IPTVPlayer"

/**
 * Décode un flux avec FFmpeg dans un thread dédié : les images sont copiées dans [bitmap]
 * (signalé par [onFrame]) et le son est envoyé à la carte audio.
 */
internal class FfmpegSession(
    private val url: String,
    /** Qualité choisie (index de variante) ou null pour automatique. */
    private val quality: String?,
    /** Piste audio choisie (index) ou null pour celle par défaut. */
    private val audio: String?,
    /** Plafond global de qualité, ou null. */
    private val maxHeight: Int?,
    private val onTracks: (qualities: List<TrackOption>, audios: List<TrackOption>, activeQuality: String?, activeAudio: String?) -> Unit,
    private val onReady: (Bitmap) -> Unit,
    private val onFrame: () -> Unit,
    private val onStatus: (PlayerStatus) -> Unit,
) {
    private class Source(val location: String, val tempFile: File?)

    /**
     * Si la chaîne propose plusieurs qualités ou pistes audio, ne donne à FFmpeg que celles retenues :
     * il ne télécharge ainsi que ce qui est lu.
     */
    private fun resolveSource(): Source {
        val (text, finalUrl) = fetchPlaylist(url) ?: return Source(url, null)
        val master = HlsMaster.parse(text, finalUrl) ?: return Source(url, null)

        // En automatique, inutile de dépasser 720p : l'affichage est de toute façon limité à cette taille.
        val variant = master.pick(quality?.toIntOrNull(), maxHeight ?: AUTO_MAX_HEIGHT)
        val audioChoices = master.audiosFor(variant)
        val audioIndex = audio?.toIntOrNull()?.takeIf { it in audioChoices } ?: master.defaultAudio(variant)
        val activeQuality = master.qualityOf(variant)

        if (running) onTracks(
            master.qualities.map { TrackOption(it.first.toString(), it.second) },
            audioChoices.mapIndexed { position, index ->
                val track = master.audios[index]
                val label = listOfNotNull(track.name.takeIf { it.isNotBlank() }, track.language?.uppercase())
                    .distinct().joinToString(" · ").ifEmpty { "Piste ${position + 1}" }
                TrackOption(index.toString(), label)
            },
            activeQuality.toString(),
            audioIndex?.toString(),
        )

        val rendition = audioIndex?.let { master.audios[it] }
        if (rendition?.url == null) return Source(variant.url, null) // son déjà inclus dans la vidéo
        val file = File.createTempFile("iptv-", ".m3u8")
        file.writeText(master.single(variant, rendition))
        return Source(file.absolutePath, file)
    }

    /** Télécharge le début de [address] s'il s'agit d'une playlist ; renvoie (texte, adresse finale après redirections). */
    private fun fetchPlaylist(address: String): Pair<String, String>? = runCatching {
        val request = HttpRequest.newBuilder(URI(address))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", USER_AGENT)
            .GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { stream ->
            if (response.statusCode() !in 200..299) return null
            val head = stream.readNBytes(7)
            if (!String(head).startsWith("#EXTM3U")) return null // flux direct : on ne lit pas plus loin
            val rest = stream.readNBytes(256 * 1024)
            String(head + rest) to response.uri().toString()
        }
    }.getOrNull()

    /** À verrouiller par quiconque lit le bitmap fourni à [onReady]. */
    val frameLock = Any()

    /** Volume appliqué au son (0 = muet, 1 = normal). */
    @Volatile
    var gain = 1f

    @Volatile
    var paused = false

    @Volatile
    private var running = true
    private val thread = Thread(::run, "iptv-player").apply { isDaemon = true }

    fun start() = thread.start()

    fun stop() {
        running = false
    }

    private fun report(status: PlayerStatus) {
        if (running) onStatus(status)
    }

    /** Ouvre [location] avec FFmpeg ; lève une exception si le flux ne démarre pas. */
    private fun open(location: String, localPlaylist: Boolean): FFmpegFrameGrabber {
        val grabber = FFmpegFrameGrabber(location)
        try {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
            if (localPlaylist) {
                // Playlist locale qui pointe vers des flux distants : FFmpeg le refuse sans autorisation explicite.
                grabber.setOption("protocol_whitelist", "file,http,https,tcp,tls,crypto,data")
            }
            grabber.setOption("rw_timeout", "15000000")
            grabber.setOption("user_agent", USER_AGENT)
            // Démarrage plus rapide : analyse du flux limitée à 2 s.
            grabber.setOption("analyzeduration", "2000000")
            grabber.setOption("probesize", "2000000")
            grabber.pixelFormat = avutil.AV_PIX_FMT_BGRA
            grabber.sampleFormat = avutil.AV_SAMPLE_FMT_S16
            grabber.audioChannels = 2
            grabber.start()
            return grabber
        } catch (e: Throwable) {
            runCatching { grabber.close() }
            throw e
        }
    }

    private fun run() {
        val source = resolveSource()
        val grabber = try {
            open(source.location, source.tempFile != null)
        } catch (e: Throwable) {
            // La qualité isolée ne s'ouvre pas (certains serveurs l'interdisent) : on lit l'adresse
            // d'origine telle quelle, sans choix de qualité ni de piste.
            val fallback = if (source.location != url && running) runCatching { open(url, false) }.getOrNull() else null
            source.tempFile?.delete()
            if (fallback == null) {
                report(PlayerStatus.Error)
                return
            }
            if (running) onTracks(emptyList(), emptyList(), null, null)
            fallback
        }
        var line: SourceDataLine? = null
        try {
            if (!running) return

            // Limite la taille de sortie : inutile de convertir du 1080p image par image.
            if (grabber.imageWidth > MAX_WIDTH) {
                val height = grabber.imageHeight * MAX_WIDTH / grabber.imageWidth
                grabber.imageWidth = MAX_WIDTH
                grabber.imageHeight = height - height % 2
            }
            val width = grabber.imageWidth
            val height = grabber.imageHeight
            var bitmap: Bitmap? = null
            var pixels = ByteArray(0)

            if (grabber.sampleRate > 0 && grabber.hasAudio()) {
                line = runCatching {
                    val format = AudioFormat(grabber.sampleRate.toFloat(), 16, 2, true, false)
                    AudioSystem.getSourceDataLine(format).apply {
                        open(format, grabber.sampleRate * 2) // ~0,5 s de tampon
                        start()
                    }
                }.getOrNull()
            }

            var wallStart = -1L
            var streamStart = 0L
            var audio = ByteArray(0)

            while (running) {
                if (paused) {
                    // Pause : on arrête de lire le flux et on vide le son déjà en attente.
                    line?.flush()
                    while (paused && running) Thread.sleep(40)
                    wallStart = -1 // l'horloge d'affichage repart à la reprise
                }
                val frame: Frame = grabber.grab() ?: break
                val image = frame.image
                val samples = frame.samples
                if (image != null && width > 0 && height > 0) {
                    // Cadence l'affichage sur les horodatages du flux.
                    val now = System.nanoTime()
                    if (wallStart < 0) {
                        wallStart = now
                        streamStart = frame.timestamp
                        report(PlayerStatus.Playing)
                    }
                    val dueNanos = (frame.timestamp - streamStart) * 1000 - (now - wallStart)
                    when {
                        dueNanos in 1..1_000_000_000 -> Thread.sleep(dueNanos / 1_000_000)
                        dueNanos > 1_000_000_000 || dueNanos < -500_000_000 -> {
                            wallStart = now
                            streamStart = frame.timestamp
                        }
                    }

                    val source = (image[0] as ByteBuffer).duplicate()
                    val stride = frame.imageStride
                    val needed = stride * frame.imageHeight
                    if (pixels.size != needed) pixels = ByteArray(needed)
                    source.position(0)
                    source.get(pixels, 0, minOf(needed, source.remaining()))

                    val target = bitmap ?: Bitmap().also {
                        it.allocPixels(
                            ImageInfo(frame.imageWidth, frame.imageHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
                            stride,
                        )
                        bitmap = it
                        if (running) onReady(it)
                    }
                    // Le thread d'affichage lit ce bitmap : jamais d'écriture pendant un dessin.
                    synchronized(frameLock) {
                        target.installPixels(target.imageInfo, pixels, stride)
                        target.notifyPixelsChanged()
                    }
                    if (running) onFrame()
                } else if (samples != null && line != null) {
                    val buffer = samples[0] as ShortBuffer
                    val count = buffer.remaining()
                    if (audio.size < count * 2) audio = ByteArray(count * 2)
                    val volume = gain
                    for (i in 0 until count) {
                        val sample = (buffer.get(buffer.position() + i) * volume).toInt()
                        audio[i * 2] = sample.toByte()
                        audio[i * 2 + 1] = (sample shr 8).toByte()
                    }
                    line.write(audio, 0, count * 2)
                }
            }
            report(PlayerStatus.Error) // fin de flux inattendue
        } catch (e: Throwable) {
            report(PlayerStatus.Error)
        } finally {
            runCatching { line?.stop(); line?.close() }
            runCatching { grabber.close() }
            source.tempFile?.delete()
        }
    }
}
