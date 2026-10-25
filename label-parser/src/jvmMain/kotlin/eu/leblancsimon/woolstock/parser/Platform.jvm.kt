package eu.leblancsimon.woolstock.parser

import java.text.Normalizer

internal actual fun canonicalDecomposition(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
