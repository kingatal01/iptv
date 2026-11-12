package com.yamking.iptv.data

/** Parseur de playlists M3U / M3U8 étendues (#EXTINF avec attributs tvg-*). */
object M3uParser {
    private val attribute = Regex("""([\w-]+)="([^"]*)"""")
    private val qualityTag = Regex("""\s*\((\d{3,4}p)\)""")
    private val otherTags = Regex("""\s*\[[^\]]*]""")

    fun parse(text: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var pending: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> pending = line
                line.startsWith("#") -> Unit
                else -> {
                    val info = pending
                    pending = null
                    if (info != null && isStreamUrl(line)) channels += build(info, line)
                }
            }
        }
        return channels
    }

    private fun isStreamUrl(line: String) =
        line.startsWith("http://", true) || line.startsWith("https://", true)

    private fun build(info: String, url: String): Channel {
        val attrs = attribute.findAll(info).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        // Le nom suit le dernier attribut ; certaines lignes mal formées contiennent des virgules avant.
        val afterAttributes = info.lastIndexOf("\",")
        val rawName = (if (afterAttributes >= 0) info.substring(afterAttributes + 2) else info.substringAfter(',', "")).trim()
        val quality = qualityTag.find(rawName)?.groupValues?.get(1)
        val name = otherTags.replace(qualityTag.replace(rawName, ""), "").trim()
            .ifEmpty { attrs["tvg-name"].orEmpty() }
            .ifEmpty { "Sans nom" }
        val groups = attrs["group-title"].orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return Channel(
            name = name,
            url = url,
            logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") },
            groups = groups,
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotEmpty() },
            quality = quality,
            country = country(attrs),
            language = attrs["tvg-language"]?.split(';', ',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Pays déclaré (tvg-country) ou, à défaut, suffixe de l'identifiant : « Canal.fr@HD » → FR. */
    private fun country(attrs: Map<String, String>): String? {
        val declared = attrs["tvg-country"]?.split(';', ',')?.firstOrNull()?.trim()
        val code = declared?.takeIf { it.length == 2 }
            ?: attrs["tvg-id"]?.let { idCountry.find(it)?.groupValues?.get(1) }
            ?: return null
        return code.uppercase().let { if (it == "UK") "GB" else it }.takeIf { c -> c.all { it in 'A'..'Z' } }
    }

    /** Pays déduit du seul identifiant de chaîne (« Canal.fr@HD » → FR), ou null. */
    fun countryOf(tvgId: String?): String? = tvgId?.let { country(mapOf("tvg-id" to it)) }

    private val idCountry = Regex("""\.([A-Za-z]{2})(@.*)?$""")

    /** Écrit une playlist M3U relisible par [parse] et par les autres lecteurs IPTV. */
    fun write(channels: List<Channel>): String = buildString {
        append("#EXTM3U\n")
        for (channel in channels) {
            append("#EXTINF:-1")
            channel.tvgId?.let { append(" tvg-id=\"").append(clean(it)).append('"') }
            channel.logo?.let { append(" tvg-logo=\"").append(clean(it)).append('"') }
            channel.country?.let { append(" tvg-country=\"").append(clean(it)).append('"') }
            channel.language?.let { append(" tvg-language=\"").append(clean(it)).append('"') }
            append(" group-title=\"").append(clean(channel.groups.joinToString(";"))).append("\",")
            append(channel.name)
            channel.quality?.let { append(" (").append(it).append(')') }
            append('\n').append(channel.url).append('\n')
        }
    }

    private fun clean(value: String) = value.replace('"', '\'').replace('\n', ' ')
}