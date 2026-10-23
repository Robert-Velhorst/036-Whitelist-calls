package com.whitelistcalls

import android.content.Context
import android.telephony.PhoneNumberUtils
import java.util.Base64

data class WhitelistEntry(
    val number: String,
    val contactName: String?,
)

/** Merges imported entries without replacing labels chosen in the destination list. */
internal fun mergeWhatsappWhitelistEntries(
    existing: List<WhitelistEntry>,
    shared: List<WhitelistEntry>,
): List<WhitelistEntry> {
    val entriesByNumber = existing.associateBy(WhitelistEntry::number).toMutableMap()
    shared.forEach { entry -> entriesByNumber.putIfAbsent(entry.number, entry) }
    return entriesByNumber.values.sortedBy(WhitelistEntry::number)
}

enum class WhitelistUpdateResult {
    UPDATED,
    INVALID_NUMBER,
    DUPLICATE_NUMBER,
    NOT_FOUND,
}

/**
 * Contact labels are metadata only. The call-screening path continues to use
 * the phone-number set as its single source of truth for matching.
 */
internal class WhitelistEntryMetadata private constructor(
    private val namesByNumber: Map<String, String>,
) {
    fun nameFor(number: String): String? = namesByNumber[number]

    fun moveNumber(
        oldNumber: String,
        newNumber: String,
        name: String?,
    ): WhitelistEntryMetadata {
        val updated = namesByNumber.toMutableMap()
        updated.remove(oldNumber)
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { updated[newNumber] = it }
        return from(updated)
    }

    fun remove(number: String): WhitelistEntryMetadata = moveNumber(number, number, null)

    fun asMap(): Map<String, String> = namesByNumber

    companion object {
        fun from(namesByNumber: Map<String, String>): WhitelistEntryMetadata = WhitelistEntryMetadata(
            namesByNumber.mapNotNull { (number, name) ->
                name.trim().takeIf { it.isNotEmpty() }?.let { number to it }
            }.toMap(),
        )
    }
}

/**
 * Small, synchronous storage used directly from CallScreeningService.
 * Never perform network or database work on the screening path: Android allows
 * only five seconds to answer an incoming call.
 */
class WhitelistStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isProtectionEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setProtectionEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun allowedNumbers(): List<String> =
        preferences.getStringSet(KEY_NUMBERS, emptySet())!!.sorted()

    fun allowedEntries(): List<WhitelistEntry> {
        val metadata = readContactMetadata()
        return allowedNumbers().map { number ->
            WhitelistEntry(number = number, contactName = metadata.nameFor(number))
        }
    }

    fun add(rawNumber: String, contactName: String? = null): Boolean {
        val number = normalize(rawNumber) ?: return false
        val updated = preferences.getStringSet(KEY_NUMBERS, emptySet())!!.toMutableSet()
        val added = updated.add(number)
        if (added) {
            val metadata = readContactMetadata().moveNumber(number, number, contactName)
            saveWhitelist(updated, metadata)
        }
        return added
    }

    fun remove(number: String) {
        val updated = preferences.getStringSet(KEY_NUMBERS, emptySet())!!.toMutableSet()
        updated.remove(number)
        saveWhitelist(updated, readContactMetadata().remove(number))
    }

    fun update(
        oldNumber: String,
        rawNewNumber: String,
        contactName: String?,
    ): WhitelistUpdateResult {
        val newNumber = normalize(rawNewNumber) ?: return WhitelistUpdateResult.INVALID_NUMBER
        val updated = preferences.getStringSet(KEY_NUMBERS, emptySet())!!.toMutableSet()
        if (oldNumber !in updated) return WhitelistUpdateResult.NOT_FOUND
        if (newNumber != oldNumber && newNumber in updated) {
            return WhitelistUpdateResult.DUPLICATE_NUMBER
        }

        updated.remove(oldNumber)
        updated.add(newNumber)
        val metadata = readContactMetadata().moveNumber(oldNumber, newNumber, contactName)
        saveWhitelist(updated, metadata)
        return WhitelistUpdateResult.UPDATED
    }

    fun isAllowed(rawNumber: String?): Boolean {
        return isCellularCallAllowed(isProtectionEnabled()) { isWhitelisted(rawNumber) }
    }

    fun isWhitelisted(rawNumber: String?): Boolean = WhitelistMembership(
        normalizedNumbers = preferences.getStringSet(KEY_NUMBERS, emptySet())!!,
        normalize = ::normalize,
    ).contains(rawNumber)

    fun hasShownFirstToggleDialog(): Boolean = preferences.getBoolean(KEY_FIRST_TOGGLE_DIALOG, false)

    fun setFirstToggleDialogShown() {
        preferences.edit().putBoolean(KEY_FIRST_TOGGLE_DIALOG, true).apply()
    }

    /** Store E.164-style entries where possible, e.g. +31612345678. */
    fun normalize(rawNumber: String?): String? = normalizePhoneNumber(rawNumber)

    private fun readContactMetadata(): WhitelistEntryMetadata {
        val names = preferences.getStringSet(KEY_CONTACT_NAMES, emptySet())!!
            .mapNotNull(::decodeContactName)
            .toMap()
        return WhitelistEntryMetadata.from(names)
    }

    private fun saveWhitelist(numbers: Set<String>, metadata: WhitelistEntryMetadata) {
        preferences.edit()
            .putStringSet(KEY_NUMBERS, numbers)
            .putStringSet(KEY_CONTACT_NAMES, metadata.asMap().map(::encodeContactName).toSet())
            .apply()
    }

    private fun encodeContactName(entry: Map.Entry<String, String>): String = listOf(
        Base64.getUrlEncoder().withoutPadding().encodeToString(entry.key.toByteArray(Charsets.UTF_8)),
        Base64.getUrlEncoder().withoutPadding().encodeToString(entry.value.toByteArray(Charsets.UTF_8)),
    ).joinToString(".")

    private fun decodeContactName(encoded: String): Pair<String, String>? = runCatching {
        val parts = encoded.split('.', limit = 2)
        if (parts.size != 2) return@runCatching null
        val number = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
        val name = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        number to name
    }.getOrNull()

    private companion object {
        const val PREFERENCES = "call_whitelist"
        const val KEY_ENABLED = "protection_enabled"
        const val KEY_NUMBERS = "allowed_numbers"
        const val KEY_CONTACT_NAMES = "allowed_contact_names"
        const val KEY_FIRST_TOGGLE_DIALOG = "first_toggle_dialog_shown"
    }
}

/** Normalizes numbers before they are stored or matched against a whitelist. */
internal fun normalizePhoneNumber(rawNumber: String?): String? {
    if (rawNumber.isNullOrBlank()) return null
    val normalized = PhoneNumberUtils.normalizeNumber(rawNumber)
    return normalized.takeIf { it.isNotBlank() }
}

internal fun isCellularCallAllowed(
    protectionEnabled: Boolean,
    isWhitelisted: () -> Boolean,
): Boolean = !protectionEnabled || isWhitelisted()

internal class WhitelistMembership(
    private val normalizedNumbers: Set<String>,
    private val normalize: (String?) -> String?,
) {
    constructor(normalizedNumbers: Set<String>) : this(normalizedNumbers, ::normalizePhoneNumber)

    fun contains(rawNumber: String?): Boolean {
        val incomingNormalized = normalize(rawNumber) ?: return false

        if (incomingNormalized in normalizedNumbers) return true

        val incomingDigits = incomingNormalized.replace("+", "")
        if (incomingDigits.length < SIGNIFICANT_DIGITS) return false
        val incomingSuffix = incomingDigits.takeLast(SIGNIFICANT_DIGITS)

        return normalizedNumbers.any { whitelistNumber ->
            val whitelistDigits = whitelistNumber.replace("+", "")
            whitelistDigits.length >= SIGNIFICANT_DIGITS &&
                whitelistDigits.takeLast(SIGNIFICANT_DIGITS) == incomingSuffix
        }
    }

    private companion object {
        const val SIGNIFICANT_DIGITS = 9
    }
}
