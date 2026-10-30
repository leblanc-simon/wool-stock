package io.leblanc.woolstock.platform

/** Nom de l'appareil, noté avec la session pour la reconnaître parmi les autres. */
expect fun deviceName(): String

/** Langue de l'appareil (« fr-FR ») : le serveur traduit ses messages d'erreur et l'export. */
expect fun languageTag(): String

/** Date et heure d'un instant, dans le fuseau et au format court de l'appareil. */
expect fun formatDateTime(epochMillis: Long): String
