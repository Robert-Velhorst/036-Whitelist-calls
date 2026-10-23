package com.whitelistcalls

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppWhitelistEntriesTest {
    @Test
    fun `merging shared entries adds new labels without overwriting a custom label`() {
        val merged = mergeWhatsappWhitelistEntries(
            existing = listOf(WhitelistEntry("+31612345678", "Custom label")),
            shared = listOf(
                WhitelistEntry("+31612345678", "Shared label"),
                WhitelistEntry("+31687654321", "Imported contact"),
            ),
        ).associateBy(WhitelistEntry::number)

        assertEquals("Custom label", merged.getValue("+31612345678").contactName)
        assertEquals("Imported contact", merged.getValue("+31687654321").contactName)
    }
}
