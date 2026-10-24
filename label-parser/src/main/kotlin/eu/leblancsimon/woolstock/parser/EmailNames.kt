package eu.leblancsimon.woolstock.parser

import java.util.Locale

/** Devine un nom à partir d'une adresse email, pour préremplir la création d'un compte. */
object EmailNames {
    private val WORD_SEPARATORS = Regex("[._]+")

    /**
     * « jean-luc.dupont+laine@exemple.fr » donne « Jean-Luc Dupont » : la partie avant
     * « @ » (sans « +étiquette »), découpée sur les points et soulignés, sans chiffres,
     * chaque mot et chaque partie de nom composé avec une majuscule. Vide si rien n'en sort.
     */
    fun guess(email: String, locale: Locale = Locale.getDefault()): String {
        val local = email.trim().substringBefore('@').substringBefore('+')
        return local.split(WORD_SEPARATORS)
            .map { word -> word.filterNot(Char::isDigit).trim('-') }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.split('-').filter { it.isNotEmpty() }.joinToString("-") { part ->
                    part.lowercase(locale).replaceFirstChar { it.titlecase(locale) }
                }
            }
    }
}
