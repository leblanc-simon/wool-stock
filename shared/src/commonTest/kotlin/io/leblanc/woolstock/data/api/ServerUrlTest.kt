package io.leblanc.woolstock.data.api

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun releaseBuildsRequireHttpsExceptOnThePhoneItself() {
        assertEquals(ServerUrlCheck.OK, checkServerUrl("https://stock-laine.carin.family", allowCleartext = false))
        assertEquals(ServerUrlCheck.OK, checkServerUrl("http://127.0.0.1:8080", allowCleartext = false))
        assertEquals(ServerUrlCheck.OK, checkServerUrl("http://localhost:8080", allowCleartext = false))
        assertEquals(ServerUrlCheck.CLEARTEXT, checkServerUrl("http://192.168.1.10:8080", allowCleartext = false))
        assertEquals(ServerUrlCheck.CLEARTEXT, checkServerUrl("HTTP://stock.example", allowCleartext = false))
        assertEquals(ServerUrlCheck.INVALID, checkServerUrl("stock.example", allowCleartext = false))
    }

    @Test
    fun debugBuildsAcceptHttpOnTheLocalNetwork() {
        assertEquals(ServerUrlCheck.OK, checkServerUrl("http://192.168.1.10:8080", allowCleartext = true))
    }

    @Test
    fun privacyPolicyIsServedByTheServerItself() {
        assertEquals("https://stock-laine.carin.family/privacy", privacyPolicyUrl("https://stock-laine.carin.family"))
        assertEquals("http://127.0.0.1:8080/privacy", privacyPolicyUrl(" http://127.0.0.1:8080/ "))
    }
}
