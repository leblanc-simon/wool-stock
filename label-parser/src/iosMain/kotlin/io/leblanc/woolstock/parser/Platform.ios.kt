package io.leblanc.woolstock.parser

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCanonicalMapping

@OptIn(BetaInteropApi::class)
internal actual fun canonicalDecomposition(text: String): String =
    NSString.create(string = text).decomposedStringWithCanonicalMapping
