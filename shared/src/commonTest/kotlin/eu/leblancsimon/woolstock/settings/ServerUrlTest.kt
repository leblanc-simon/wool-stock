package eu.leblancsimon.woolstock.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerUrlTest {
    @Test
    fun httpAndHttpsWithAHostAreAccepted() {
        assertTrue(isHttpUrl("https://stock-laine.carin.family"))
        assertTrue(isHttpUrl("http://192.168.1.10:8080"))
        assertTrue(isHttpUrl("HTTP://127.0.0.1:8080/"))
        assertTrue(isHttpUrl("http://[::1]:8080"))
    }

    @Test
    fun anythingElseIsRefused() {
        assertFalse(isHttpUrl("stock-laine.carin.family"))
        assertFalse(isHttpUrl("ftp://example.org"))
        assertFalse(isHttpUrl("http://"))
        assertFalse(isHttpUrl("http://:8080"))
        assertFalse(isHttpUrl("http://exa mple.org"))
    }
}
