package com.whitelistcalls

import com.google.i18n.phonenumbers.PhoneNumberUtil

/** Formats phone numbers for display without changing their stored value. */
object PhoneNumberDisplayFormatter {
    private val phoneNumberUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    fun format(rawNumber: String, defaultRegion: String): String {
        val number = rawNumber.trim()
        if (number.isEmpty()) return number

        return formatValid(number, defaultRegion) ?: number
    }

    /** Returns the canonical display value only when the number is valid for its region. */
    fun formatValid(rawNumber: String, defaultRegion: String): String? {
        val number = rawNumber.trim()
        if (number.isEmpty()) return null

        return runCatching {
            val parsed = phoneNumberUtil.parse(number, defaultRegion.ifBlank { UNKNOWN_REGION })
            parsed.takeIf(phoneNumberUtil::isValidNumber)?.let {
                phoneNumberUtil
                    .format(it, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
                    .replace(SEPARATORS, " ")
            }
        }.getOrNull()
    }

    private const val UNKNOWN_REGION = "ZZ"
    private val SEPARATORS = Regex("[\\s-]+")
}
