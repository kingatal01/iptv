package com.yamking.iptv.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

/** Résultat d'un contrôle réussi ; [maxHeight] est la meilleure qualité annoncée par le flux, si connue. */
class StreamInfo(val maxHeight: Int?)

/** Télécharge les playlists et vérifie qu'une adresse de flux répond réellement. */
class StreamChecker {
    private val client = HttpClient {
        followRedirects = true
        expectSuccess = false
        install(HttpRedirect) { allowHttpsDowngrade = true }
        install(HttpTimeout) {
            connectTimeoutMillis = 7_000
            socketTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
        }
    }

    suspend fun download(url: String): String {
        // Les grosses playlists (plusieurs Mo) sur réseau mobile demandent des délais bien plus longs qu'un test de flux.
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            timeout {
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 60_000
                requestTimeoutMillis = 300_000
            }
        }
        if (!response.status.isSuccess()) error("Le serveur a répondu ${response.status.value}")
        return response.bodyAsText()
    }

    /**
     * Vérifie le flux en profondeur : playlist principale, puis playlist de qualité,
     * puis un segment vidéo. Une chaîne n'est gardée que si le segment est réellement servi.
     */
    /**
     * Télécharge le début d'une page ou d'une playlist (au plus [limit] octets) ; null si le serveur
     * refuse ou ne répond pas. Sert à la recherche de playlists, où l'on visite des adresses inconnues.
     */
    suspend fun fetchText(url: String, limit: Int = 600_000): String? = try {
        withTimeoutOrNull(20_000) {
            client.prepareGet(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.AcceptLanguage, "fr-FR,fr;q=0.9,en;q=0.8")
            }.execute { response ->
                if (!response.status.isSuccess()) return@execute null
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(limit)
                var total = 0
                while (total < limit) {
                    val read = channel.readAvailable(buffer, total, limit - total)
                    if (read <= 0) break
                    total += read
                }
                buffer.decodeToString(0, total)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    suspend fun isAlive(url: String): Boolean = check(url) != null

    /** Renvoie les informations du flux s'il est réellement lisible, sinon null. */
    suspend fun check(url: String): StreamInfo? = try {
        withTimeoutOrNull(25_000) { deepCheck(url) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    private suspend fun deepCheck(url: String): StreamInfo? {
        var current = url
        var maxHeight: Int? = null
        repeat(3) { depth ->
            val probe = fetch(current, PLAYLIST_LIMIT) ?: return null
            if (probe.bytes.isEmpty()) return null
            val text = probe.bytes.decodeToString().trimStart('\uFEFF', ' ', '\r', '\n')
            if (!text.startsWith("#EXTM3U")) {
                // Flux direct (MPEG-TS, MP4…) : il doit ressembler à de la vidéo, pas à une page d'erreur.
                val ok = depth == 0 && (looksLikeMedia(probe.bytes) || isMediaType(probe.type))
                return if (ok) StreamInfo(null) else null
            }
            val master = HlsMaster.parse(text, probe.finalUrl)
            if (master != null) {
                // Qualité réellement diffusée, relevée sur le flux et non sur le nom de la chaîne.
                maxHeight = master.variants.mapNotNull { it.height }.maxOrNull()
                current = master.variants.first().url
            } else {
                val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                val uris = lines.filter { !it.startsWith("#") }
                if (uris.isEmpty()) return null
                // Playlist de segments : on télécharge le début du plus récent et on vérifie que c'est de la vidéo.
                val segment = fetch(resolve(probe.finalUrl, uris.last()), 2048) ?: return null
                val encrypted = lines.any { it.startsWith("#EXT-X-KEY") && "METHOD=NONE" !in it }
                val ok = segment.bytes.isNotEmpty() && !isTextType(segment.type) &&
                    (encrypted || looksLikeMedia(segment.bytes) || isMediaType(segment.type))
                return if (ok) StreamInfo(maxHeight) else null
            }
        }
        return null
    }

    /** Reconnaît les premiers octets d'un segment MPEG-TS, MP4 fragmenté ou audio (AAC, MP3). */
    private fun looksLikeMedia(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val ts = bytes[0] == 0x47.toByte() && (bytes.size <= 188 || bytes[188] == 0x47.toByte())
        val box = bytes.decodeToString(4, 8)
        val mp4 = box in setOf("ftyp", "styp", "moof", "sidx", "moov", "emsg", "prft")
        val id3 = bytes.decodeToString(0, 3) == "ID3"
        val adts = bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xF0) == 0xF0
        return ts || mp4 || id3 || adts
    }

    private class Probe(val finalUrl: String, val type: String, val bytes: ByteArray)

    private suspend fun fetch(url: String, limit: Int): Probe? =
        client.prepareGet(url) { header(HttpHeaders.UserAgent, USER_AGENT) }.execute { response ->
            if (!response.status.isSuccess()) return@execute null
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(limit)
            var total = 0
            while (total < limit) {
                val read = channel.readAvailable(buffer, total, limit - total)
                if (read <= 0) break
                total += read
                // Inutile de lire davantage si ce n'est pas une playlist texte.
                if (total >= 7 && !buffer.decodeToString(0, minOf(total, 16)).trimStart('\uFEFF').startsWith("#EXTM3U")) break
            }
            Probe(
                finalUrl = response.request.url.toString(),
                type = response.contentType()?.toString()?.lowercase().orEmpty(),
                bytes = buffer.copyOf(total),
            )
        }

    private fun isTextType(type: String) = type.startsWith("text/") || "json" in type || "xml" in type

    private fun isMediaType(type: String) =
        type.startsWith("video/") || type.startsWith("audio/") || "octet-stream" in type || "mp2t" in type

    companion object {
        private const val PLAYLIST_LIMIT = 256 * 1024

        /** Résout une adresse relative d'une playlist HLS par rapport à l'adresse de celle-ci. */
        fun resolve(base: String, ref: String): String = when {
            ref.startsWith("http://", true) || ref.startsWith("https://", true) -> ref
            ref.startsWith("//") -> base.substringBefore("//") + ref
            ref.startsWith("/") -> {
                val schemeEnd = base.indexOf("://") + 3
                val pathStart = base.indexOf('/', schemeEnd).let { if (it < 0) base.length else it }
                base.substring(0, pathStart) + ref
            }
            else -> base.substringBefore('?').substringBeforeLast('/') + "/" + ref
        }
    }
}