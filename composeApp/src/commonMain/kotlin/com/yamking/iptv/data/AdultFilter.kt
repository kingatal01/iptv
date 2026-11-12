package com.yamking.iptv.data

/**
 * Repère le contenu pour adultes d'après les noms, catégories et mots-clés.
 * Tant que le verrou de l'application est actif, ces chaînes sont masquées et ces recherches refusées.
 * Limite assumée : une chaîne mal étiquetée par sa playlist n'est pas reconnue.
 */
object AdultFilter {
    // Mots entiers uniquement, pour ne pas confondre « Essex » ou « Sussex » avec « sex ».
    private val words = Regex(
        """(?<![\p{L}\d])(xxx|adult|adults|adulte|adultes|porn|porno|pornographie|erotic|erotica|erotique|érotique|sex|sexe|sexy|hentai|playboy|hustler|brazzers|nsfw)(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE,
    )
    private val ageMark = Regex("""(\+\s?18|18\s?\+)""")

    /** Expressions courantes qui contiennent un de ces mots sans désigner du contenu pour adultes. */
    private val harmless = listOf("adult swim", "young adult", "adult contemporary")

    fun matches(text: String): Boolean {
        var cleaned = text.lowercase()
        for (phrase in harmless) cleaned = cleaned.replace(phrase, " ")
        return words.containsMatchIn(cleaned) || ageMark.containsMatchIn(cleaned)
    }

    fun isAdult(channel: Channel): Boolean = matches(channel.name) || channel.groups.any(::matches)

    /** Vrai si l'un des mots-clés de recherche vise du contenu pour adultes. */
    fun hasAdultKeyword(keywords: List<String>): Boolean = keywords.any(::matches)
}
