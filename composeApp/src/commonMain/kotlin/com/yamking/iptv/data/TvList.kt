package com.yamking.iptv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Format d'échange propre à l'application (fichiers « .tvlist ») : une sélection de chaînes
 * avec tous leurs champs, là où le M3U perd une partie des informations.
 */
@Serializable
data class TvList(
    /** Toujours [TvListFormat.NAME] : permet de reconnaître le fichier à l'import. */
    val format: String = TvListFormat.NAME,
    val version: Int = TvListFormat.VERSION,
    /** Date de l'export, en millisecondes. */
    val exportedAt: Long = 0,
    /** Catégories choisies au moment de l'export. */
    val categories: List<String> = emptyList(),
    val channels: List<Channel> = emptyList(),
    /** Adresses des chaînes qui étaient en favoris. */
    val favorites: List<String> = emptyList(),
)

object TvListFormat {
    const val NAME = "iptv-player-list"
    const val VERSION = 1
    const val EXTENSION = "tvlist"

    /** Nom donné, dans la fenêtre d'export, aux chaînes qui n'ont aucune catégorie. */
    const val NO_CATEGORY = "Sans catégorie"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true // « format » et « version » doivent toujours être écrits
    }

    /** Chaînes de [channels] appartenant à l'une des [categories] (et favorites si [favoritesOnly]). */
    fun select(
        channels: List<Channel>,
        categories: Set<String>,
        favorites: Set<String>,
        favoritesOnly: Boolean,
    ): List<Channel> = channels.filter { channel ->
        val inCategory = if (channel.groups.isEmpty()) NO_CATEGORY in categories else channel.groups.any { it in categories }
        inCategory && (!favoritesOnly || channel.url in favorites)
    }

    fun write(list: TvList): String = json.encodeToString(TvList.serializer(), list)

    /** Renvoie null si [text] n'est pas un fichier « .tvlist ». */
    fun read(text: String): TvList? =
        runCatching { json.decodeFromString(TvList.serializer(), text) }.getOrNull()
            ?.takeIf { it.format == NAME && it.channels.isNotEmpty() }
            // Un fichier exporté sans pays (ancienne version) : on le déduit de l'identifiant, comme pour un M3U.
            ?.let { list -> list.copy(channels = list.channels.map { c -> if (c.country == null) c.copy(country = M3uParser.countryOf(c.tvgId)) else c }) }

    /** Catégories de [list] avec leur nombre de chaînes, les plus fournies d'abord. */
    fun categories(list: TvList): List<Pair<String, Int>> {
        val counts = list.channels.flatMap { it.groups }.groupingBy { it }.eachCount()
        val without = list.channels.count { it.groups.isEmpty() }
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value } +
            (if (without > 0) listOf(NO_CATEGORY to without) else emptyList())
    }
}
