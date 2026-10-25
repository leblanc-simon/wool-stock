package eu.leblancsimon.woolstock.parser

/** Décomposition canonique Unicode (NFD) : « é » devient « e » suivi de l'accent combinant. */
internal expect fun canonicalDecomposition(text: String): String
