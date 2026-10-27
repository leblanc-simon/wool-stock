package eu.leblancsimon.woolstock.data.api

import io.ktor.http.Url
import io.ktor.http.parseUrl

/** Ce que vaut une adresse de serveur saisie dans les réglages. */
enum class ServerUrlCheck {
    OK,

    /** Pas une adresse http(s) avec un hôte. */
    INVALID,

    /** En HTTP alors que cette version de l'app exige HTTPS. */
    CLEARTEXT,
}

/** Le téléphone lui-même : le poste de dev joint par `adb reverse`, comme le network_security_config d'Android. */
private val LOOPBACK = setOf("127.0.0.1", "localhost")

/** Adresse http(s) avec un hôte, que Ktor sait lire. */
internal fun isHttpUrl(url: String): Boolean {
    val rest = when {
        url.startsWith("http://", ignoreCase = true) -> url.substring("http://".length)
        url.startsWith("https://", ignoreCase = true) -> url.substring("https://".length)
        else -> return false
    }
    val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':')
    return host.isNotEmpty() && host.none(Char::isWhitespace) && parseUrl(url) != null
}

/**
 * HTTP en clair n'est permis qu'aux versions de développement ([allowCleartext]) et vers le
 * téléphone lui-même : ailleurs, le jeton de session circulerait en clair sur le réseau.
 */
internal fun cleartextAllowed(url: Url, allowCleartext: Boolean): Boolean =
    allowCleartext || !url.protocol.name.equals("http", ignoreCase = true) || url.host.lowercase() in LOOPBACK

/** Vérifie une adresse saisie : bien formée, et en HTTPS si cette version de l'app l'exige. */
fun checkServerUrl(url: String, allowCleartext: Boolean): ServerUrlCheck {
    if (!isHttpUrl(url)) return ServerUrlCheck.INVALID
    val parsed = parseUrl(url) ?: return ServerUrlCheck.INVALID
    return if (cleartextAllowed(parsed, allowCleartext)) ServerUrlCheck.OK else ServerUrlCheck.CLEARTEXT
}
