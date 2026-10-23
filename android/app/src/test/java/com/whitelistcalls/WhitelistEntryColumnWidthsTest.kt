package com.whitelistcalls

import org.junit.Assert.assertEquals
import org.junit.Test

class WhitelistEntryColumnWidthsTest {
    @Test
    fun `short name uses only its measured width and leaves the remainder to the phone`() {
        val widths = whitelistEntryColumnWidths(
            availableWidth = 500,
            nameMeasuredWidth = 120,
        )

        assertEquals(120, widths.name)
        assertEquals(380, widths.phone)
    }

    @Test
    fun `long name is capped at sixty percent and leaves forty percent for the phone`() {
        val widths = whitelistEntryColumnWidths(
            availableWidth = 500,
            nameMeasuredWidth = 420,
        )

        assertEquals(300, widths.name)
        assertEquals(200, widths.phone)
    }
}
