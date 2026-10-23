package com.whitelistcalls

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberDisplayFormatterTest {
    @Test
    fun formatsChineseMobileNumberWithInternationalCodeAndSpaces() {
        assertEquals(
            "+86 135 1234 5678",
            PhoneNumberDisplayFormatter.format("13512345678", "CN"),
        )
    }

    @Test
    fun formatsInternationalE164NumberIndependentlyOfSimRegion() {
        assertEquals(
            "+1 202 555 0123",
            PhoneNumberDisplayFormatter.format("+12025550123", "CN"),
        )
    }

    @Test
    fun addsCountryCodeAndUsesNationalGroupingForLocalForeignNumber() {
        assertEquals(
            "+44 20 7123 4567",
            PhoneNumberDisplayFormatter.format("02071234567", "GB"),
        )
    }

    @Test
    fun leavesUnparseableValuesReadable() {
        assertEquals(
            "Unknown Number",
            PhoneNumberDisplayFormatter.format("Unknown Number", "CN"),
        )
    }

    @Test
    fun returnsCanonicalDisplayValueOnlyForValidNumbers() {
        assertEquals(
            "+31 6 12345678",
            PhoneNumberDisplayFormatter.formatValid("0612345678", "NL"),
        )
    }

    @Test
    fun rejectsIncompleteNumbersBeforeTheyCanBeAdded() {
        assertEquals(
            null,
            PhoneNumberDisplayFormatter.formatValid("135123", "CN"),
        )
    }

    @Test
    fun acceptsValidLandlinesAsWhitelistNumbers() {
        assertEquals(
            "+31 20 123 4567",
            PhoneNumberDisplayFormatter.formatValid("0201234567", "NL"),
        )
    }
}
