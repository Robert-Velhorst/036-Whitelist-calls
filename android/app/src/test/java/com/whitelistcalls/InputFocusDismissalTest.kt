package com.whitelistcalls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputFocusDismissalTest {
    @Test
    fun `tap inside the focused input keeps focus`() {
        assertFalse(
            shouldDismissInputFocus(
                inputLeft = 100,
                inputTop = 200,
                inputRight = 500,
                inputBottom = 280,
                tapX = 300,
                tapY = 240,
            ),
        )
    }

    @Test
    fun `tap outside the focused input dismisses focus`() {
        assertTrue(
            shouldDismissInputFocus(
                inputLeft = 100,
                inputTop = 200,
                inputRight = 500,
                inputBottom = 280,
                tapX = 80,
                tapY = 240,
            ),
        )
    }
}
