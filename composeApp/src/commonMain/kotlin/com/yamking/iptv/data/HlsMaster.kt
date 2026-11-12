package com.yamking.iptv.data

data class HlsVariant(val url: String, val height: Int?, val bandwidth: Long, val audioGroup: String?)

data class HlsAudio(
    val group: String,
    val name: String,
    val language: String?,
    /** null : le son est déjà mélangé à la vidéo de la variante. */
    val url: String?,
    val isDefault: Boolean,
)

/** Playlist HLS « maître » : la liste des qualités (variantes) et des pistes audio d'une chaîne. */
class HlsMaster(val variants: List<HlsVariant>, val audios: List<HlsAudio>) {

    /** Une entrée par qualité distincte, de la plus haute à la plus basse ; la valeur est l'index dans [variants]. */
    val qualities: List<Pair<Int, String>> by lazy {
        val withHeight = variants.withIndex().filter { it.value.height != null }
        if (withHeight.isNotEmpty()) {
            withHeight.groupBy { it.value.height!! }
                .map { (height, same) -> same.maxBy { it.value.bandwidth }.index to "${height}p" }
                .sortedByDescending { variants[it.first].height }
        } else {
            variants.withIndex().sortedByDescending { it.value.bandwidth }
                .map { it.index to "${it.value.bandwidth / 1000} kb/s" }
        }
    }

    /**
     * Variante à lire : celle choisie par l'utilisateur, sinon la meilleure qui ne dépasse pas
     * [maxHeight] (ou la plus basse si toutes le dépassent).
     */
    fun pick(selectedIndex: Int?, maxHeight: Int?): HlsVariant {
        selectedIndex?.let { variants.getOrNull(it) }?.let { return it }
        val candidates = qualities.map { variants[it.first] }
        if (maxHeight == null) return candidates.first()
        // Sans résolution annoncée, on se repère au débit habituel de chaque qualité.
        val maxBandwidth = when {
            maxHeight >= 1080 -> 9_000_000L
            maxHeight >= 720 -> 4_500_000L
            maxHeight >= 480 -> 2_000_000L
            else -> 1_100_000L
        }
        return candidates.firstOrNull { v -> v.height?.let { it <= maxHeight } ?: (v.bandwidth <= maxBandwidth) }
            ?: candidates.last()
    }

    /** Index (dans [variants]) de l'entrée de [qualities] qui correspond à [variant]. */
    fun qualityOf(variant: HlsVariant): Int =
        qualities.firstOrNull { variants[it.first] == variant }?.first
            ?: qualities.firstOrNull { variant.height != null && variants[it.first].height == variant.height }?.first
            ?: variants.indexOf(variant)

    /** Pistes audio proposées avec [variant] (index dans [audios]). */
    fun audiosFor(variant: HlsVariant): List<Int> =
        audios.indices.filter { audios[it].group == variant.audioGroup }

    fun defaultAudio(variant: HlsVariant): Int? {
        val options = audiosFor(variant)
        return options.firstOrNull { audios[it].isDefault } ?: options.firstOrNull()
    }

    /** Playlist maître réduite à une seule qualité et une seule piste audio. */
    fun single(variant: HlsVariant, audio: HlsAudio?): String = buildString {
        append("#EXTM3U\n")
        val separate = audio?.takeIf { it.url != null }
        if (separate != null) {
            append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"")
            append(separate.url).append("\"\n")
        }
        append("#EXT-X-STREAM-INF:BANDWIDTH=").append(variant.bandwidth.coerceAtLeast(1))
        if (separate != null) append(",AUDIO=\"audio\"")
        append('\n').append(variant.url).append('\n')
    }

    companion object {
        private val attribute = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

        private fun attributes(line: String): Map<String, String> =
            attribute.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

        /** Renvoie null si [text] n'est pas une playlist maître (flux direct ou playlist de segments). */
        fun parse(text: String, baseUrl: String): HlsMaster? {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.firstOrNull()?.removePrefix("﻿")?.startsWith("#EXTM3U") != true) return null

            val variants = mutableListOf<HlsVariant>()
            val audios = mutableListOf<HlsAudio>()
            var pending: Map<String, String>? = null
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-STREAM-INF") -> pending = attributes(line)
                    line.startsWith("#EXT-X-MEDIA") -> {
                        val a = attributes(line)
                        if (a["TYPE"] == "AUDIO" && a["GROUP-ID"] != null) {
                            audios += HlsAudio(
                                group = a.getValue("GROUP-ID"),
                                name = a["NAME"].orEmpty(),
                                language = a["LANGUAGE"]?.takeIf { it.isNotEmpty() },
                                url = a["URI"]?.takeIf { it.isNotEmpty() }?.let { StreamChecker.resolve(baseUrl, it) },
                                isDefault = a["DEFAULT"] == "YES",
                            )
                        }
                    }
                    line.startsWith("#") -> Unit
                    else -> pending?.let { a ->
                        variants += HlsVariant(
                            url = StreamChecker.resolve(baseUrl, line),
                            height = a["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull(),
                            bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: 0,
                            audioGroup = a["AUDIO"],
                        )
                        pending = null
                    }
                }
            }
            return if (variants.isEmpty()) null else HlsMaster(variants, audios)
        }
    }
}
