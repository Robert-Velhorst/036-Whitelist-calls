package com.whitelistcalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhitelistEntryMetadataTest {
    @Test
    fun `contact label is returned only for the imported number`() {
        val metadata = WhitelistEntryMetadata.from(mapOf("+31612345678" to "Alexandra Example"))

        assertEquals("Alexandra Example", metadata.nameFor("+31612345678"))
        assertNull(metadata.nameFor("+31687654321"))
    }

    @Test
    fun `editing a labeled number moves its contact label to the replacement number`() {
        val metadata = WhitelistEntryMetadata.from(mapOf("+31612345678" to "Alexandra Example"))

        val updated = metadata.moveNumber(
            oldNumber = "+31612345678",
            newNumber = "+31687654321",
            name = "Alexandra Example",
        )

        assertNull(updated.nameFor("+31612345678"))
        assertEquals("Alexandra Example", updated.nameFor("+31687654321"))
    }

    @Test
    fun `empty name removes the contact label`() {
        val metadata = WhitelistEntryMetadata.from(mapOf("+31612345678" to "Alexandra Example"))

        val updated = metadata.moveNumber(
            oldNumber = "+31612345678",
            newNumber = "+31612345678",
            name = "   ",
        )

        assertNull(updated.nameFor("+31612345678"))
    }
}
