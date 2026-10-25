package eu.leblancsimon.woolstock.data.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttachmentNameTest {
    @Test
    fun encodedNameWinsOverThePlainOne() {
        assertEquals(
            "stock-laine-2026-10-25.xlsx",
            ApiClient.attachmentName("""attachment; filename="stock.xlsx"; filename*=UTF-8''stock-laine-2026-10-25.xlsx"""),
        )
        assertEquals("Stock laine é.xlsx", ApiClient.attachmentName("attachment; filename*=UTF-8''Stock%20laine%20%C3%A9.xlsx"))
    }

    @Test
    fun pathsAreDropped() {
        assertEquals("stock.xlsx", ApiClient.attachmentName("""attachment; filename="../../stock.xlsx""""))
        assertNull(ApiClient.attachmentName("""attachment; filename="..""""))
        assertNull(ApiClient.attachmentName(null))
    }
}
