package com.whitelistcalls

import android.content.Context
import android.content.SharedPreferences
import com.whitelistcalls.externalcalls.providerFor
import com.whitelistcalls.externalcalls.ExternalCallProvider
import java.util.Base64
import java.util.Locale

enum class WhatsAppWhitelistMode { SHARED, CUSTOM }

internal fun modeFrom(storedValue: String?): WhatsAppWhitelistMode {
    val value = storedValue ?: return WhatsAppWhitelistMode.SHARED
    return runCatching { WhatsAppWhitelistMode.valueOf(value) }
        .getOrDefault(WhatsAppWhitelistMode.SHARED)
}

internal fun isWhatsAppFilteringEffective(
    masterEnabled: Boolean,
    whatsappEnabled: Boolean,
): Boolean = masterEnabled && whatsappEnabled

enum class WhatsAppSetupStatus(
    val label: String,
    val detail: String,
    val isReady: Boolean,
) {
    UNCONFIGURED(
        "Not configured",
        "Enable WhatsApp call filtering to start setup.",
        false,
    ),
    PERMISSION_REQUIRED(
        "Permission required",
        "Grant notification access so incoming WhatsApp calls can be detected.",
        false,
    ),
    READY(
        "Ready",
        "WhatsApp call filtering is enabled and notification access is granted.",
        true,
    ),
    DIRECT_DECLINE_UNAVAILABLE(
        "Direct rejection unavailable",
        "The latest incoming call did not provide Android's direct decline action.",
        false,
    ),
    ;

    companion object {
        fun from(
            filteringEnabled: Boolean,
            notificationAccessGranted: Boolean,
            directDeclineUnavailable: Boolean,
        ): WhatsAppSetupStatus = when {
            !filteringEnabled -> UNCONFIGURED
            !notificationAccessGranted -> PERMISSION_REQUIRED
            directDeclineUnavailable -> DIRECT_DECLINE_UNAVAILABLE
            else -> READY
        }
    }
}

class WhatsAppSettingsStore internal constructor(
    private val preferences: SharedPreferences,
    private val defaultRegion: String = Locale.getDefault().country,
) {
    constructor(context: Context) : this(
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
    )

    fun isFilteringEnabled(): Boolean = preferences.getBoolean(KEY_FILTERING_ENABLED, false)

    fun setFilteringEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_FILTERING_ENABLED, enabled).apply()
        if (!enabled) {
            AllowedCallVibrationController.cancelProviderAlert(ExternalCallProvider.WHATSAPP)
            AllowedCallVibrationController.cancelProviderAlert(ExternalCallProvider.WHATSAPP_BUSINESS)
        }
    }

    fun whitelistMode(): WhatsAppWhitelistMode =
        modeFrom(preferences.getString(KEY_WHITELIST_MODE, null))

    fun setWhitelistMode(mode: WhatsAppWhitelistMode) {
        preferences.edit().putString(KEY_WHITELIST_MODE, mode.name).apply()
    }

    fun customNumbers(): Set<String> =
        preferences.getStringSet(KEY_CUSTOM_ALLOWED_NUMBERS, emptySet())!!.toSet()

    fun customEntries(): List<WhitelistEntry> {
        val metadata = readCustomMetadata()
        return customNumbers().sorted().map { number ->
            WhitelistEntry(number = number, contactName = metadata.nameFor(number))
        }
    }

    internal fun customMembership(normalize: (String?) -> String?): WhitelistMembership =
        WhitelistMembership(customNumbers(), normalize)

    fun replaceCustomNumbers(numbers: Set<String>) {
        saveCustomWhitelist(numbers, WhitelistEntryMetadata.from(emptyMap()))
    }

    fun mergeSharedNumbers(sharedNumbers: Set<String>) {
        saveCustomWhitelist(customNumbers() + sharedNumbers, readCustomMetadata())
    }

    fun replaceCustomEntries(entries: List<WhitelistEntry>) {
        val normalizedEntries = entries.mapNotNull { entry ->
            normalizedValidCustomNumber(entry.number)?.let { number ->
                WhitelistEntry(number, entry.contactName)
            }
        }
        val metadata = WhitelistEntryMetadata.from(
            normalizedEntries.associate { entry -> entry.number to entry.contactName.orEmpty() },
        )
        saveCustomWhitelist(normalizedEntries.map(WhitelistEntry::number).toSet(), metadata)
    }

    fun mergeSharedEntries(sharedEntries: List<WhitelistEntry>) {
        replaceCustomEntries(mergeWhatsappWhitelistEntries(customEntries(), sharedEntries))
    }

    fun addCustomNumber(rawNumber: String, contactName: String? = null): Boolean {
        val number = normalizedValidCustomNumber(rawNumber) ?: return false
        val updated = customNumbers().toMutableSet()
        val added = updated.add(number)
        if (added) {
            saveCustomWhitelist(updated, readCustomMetadata().moveNumber(number, number, contactName))
        }
        return added
    }

    fun removeCustomNumber(persistedNumber: String): Boolean {
        val updated = customNumbers().toMutableSet()
        val removed = updated.remove(persistedNumber)
        if (removed) saveCustomWhitelist(updated, readCustomMetadata().remove(persistedNumber))
        return removed
    }

    fun updateCustomEntry(
        oldNumber: String,
        rawNewNumber: String,
        contactName: String?,
    ): WhitelistUpdateResult {
        val newNumber = normalizedValidCustomNumber(rawNewNumber)
            ?: return WhitelistUpdateResult.INVALID_NUMBER
        val updated = customNumbers().toMutableSet()
        if (oldNumber !in updated) return WhitelistUpdateResult.NOT_FOUND
        if (newNumber != oldNumber && newNumber in updated) {
            return WhitelistUpdateResult.DUPLICATE_NUMBER
        }

        updated.remove(oldNumber)
        updated.add(newNumber)
        saveCustomWhitelist(updated, readCustomMetadata().moveNumber(oldNumber, newNumber, contactName))
        return WhitelistUpdateResult.UPDATED
    }

    fun isDiagnosticsEnabled(): Boolean = preferences.getBoolean(KEY_DIAGNOSTICS_ENABLED, false)

    fun setDiagnosticsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DIAGNOSTICS_ENABLED, enabled).apply()
    }

    fun isDirectDeclineUnavailable(): Boolean =
        preferences.getBoolean(KEY_DIRECT_DECLINE_UNAVAILABLE, false)

    fun setDirectDeclineUnavailable(unavailable: Boolean) {
        preferences.edit().putBoolean(KEY_DIRECT_DECLINE_UNAVAILABLE, unavailable).apply()
    }

    fun callChannelInfo(packageName: String): ExternalCallChannelInfo? {
        val channelId = preferences.getString(channelIdKey(packageName), null) ?: return null
        return ExternalCallChannelInfo(
            packageName = packageName,
            channelId = channelId,
            detectedAtMillis = preferences.getLong(channelDetectedAtKey(packageName), 0L),
            observedAlertState = observedAlertState(packageName),
        )
    }

    fun observedCallChannels(): List<ExternalCallChannelInfo> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith(KEY_CALL_CHANNEL_ID_PREFIX) }
        .map { it.removePrefix(KEY_CALL_CHANNEL_ID_PREFIX) }
        .filter { providerFor(it) != null }
        .mapNotNull(::callChannelInfo)
        .sortedByDescending(ExternalCallChannelInfo::detectedAtMillis)
        .toList()

    fun recordCallChannel(info: ExternalCallChannelInfo) {
        val editor = preferences.edit()
            .putString(channelIdKey(info.packageName), info.channelId)
            .putLong(channelDetectedAtKey(info.packageName), info.detectedAtMillis)
        info.observedAlertState?.let { state ->
            editor
                .putBoolean(channelVibratesKey(info.packageName), state.vibrates)
                .putBoolean(channelHasSoundKey(info.packageName), state.hasSound)
                .putInt(channelImportanceKey(info.packageName), state.importance)
                .putInt(channelLockscreenVisibilityKey(info.packageName), state.lockscreenVisibility)
        } ?: editor
            .remove(channelVibratesKey(info.packageName))
            .remove(channelHasSoundKey(info.packageName))
            .remove(channelImportanceKey(info.packageName))
            .remove(channelLockscreenVisibilityKey(info.packageName))
        editor.apply()
    }

    fun clearCallChannelInfo(packageName: String) {
        preferences.edit()
            .remove(channelIdKey(packageName))
            .remove(channelDetectedAtKey(packageName))
            .remove(channelVibratesKey(packageName))
            .remove(channelHasSoundKey(packageName))
            .remove(channelImportanceKey(packageName))
            .remove(channelLockscreenVisibilityKey(packageName))
            .apply()
    }

    fun status(
        notificationAccessGranted: Boolean,
        directDeclineUnavailable: Boolean,
    ): WhatsAppSetupStatus = WhatsAppSetupStatus.from(
        filteringEnabled = isFilteringEnabled(),
        notificationAccessGranted = notificationAccessGranted,
        directDeclineUnavailable = directDeclineUnavailable,
    )

    private fun normalizedValidCustomNumber(rawNumber: String): String? =
        PhoneNumberDisplayFormatter.formatValid(rawNumber, defaultRegion)
            ?.filter { it.isDigit() || it == '+' }
            ?.takeIf(String::isNotBlank)

    private fun readCustomMetadata(): WhitelistEntryMetadata {
        val names = preferences.getStringSet(KEY_CUSTOM_CONTACT_NAMES, emptySet())!!
            .mapNotNull(::decodeContactName)
            .toMap()
        return WhitelistEntryMetadata.from(names)
    }

    private fun saveCustomWhitelist(numbers: Set<String>, metadata: WhitelistEntryMetadata) {
        preferences.edit()
            .putStringSet(KEY_CUSTOM_ALLOWED_NUMBERS, numbers)
            .putStringSet(KEY_CUSTOM_CONTACT_NAMES, metadata.asMap().map(::encodeContactName).toSet())
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

    private fun channelIdKey(packageName: String): String = "$KEY_CALL_CHANNEL_ID_PREFIX$packageName"

    private fun channelDetectedAtKey(packageName: String): String =
        "$KEY_CALL_CHANNEL_DETECTED_AT_PREFIX$packageName"

    private fun observedAlertState(packageName: String): ChannelAlertState? {
        val keys = listOf(
            channelVibratesKey(packageName),
            channelHasSoundKey(packageName),
            channelImportanceKey(packageName),
            channelLockscreenVisibilityKey(packageName),
        )
        if (keys.any { key -> !preferences.contains(key) }) return null

        return ChannelAlertState(
            vibrates = preferences.getBoolean(channelVibratesKey(packageName), false),
            hasSound = preferences.getBoolean(channelHasSoundKey(packageName), false),
            importance = preferences.getInt(channelImportanceKey(packageName), 0),
            lockscreenVisibility = preferences.getInt(channelLockscreenVisibilityKey(packageName), 0),
        )
    }

    private fun channelVibratesKey(packageName: String): String =
        "$KEY_CALL_CHANNEL_VIBRATES_PREFIX$packageName"

    private fun channelHasSoundKey(packageName: String): String =
        "$KEY_CALL_CHANNEL_HAS_SOUND_PREFIX$packageName"

    private fun channelImportanceKey(packageName: String): String =
        "$KEY_CALL_CHANNEL_IMPORTANCE_PREFIX$packageName"

    private fun channelLockscreenVisibilityKey(packageName: String): String =
        "$KEY_CALL_CHANNEL_LOCKSCREEN_VISIBILITY_PREFIX$packageName"

    private companion object {
        const val PREFERENCES = "whatsapp_settings"
        const val KEY_FILTERING_ENABLED = "filtering_enabled"
        const val KEY_WHITELIST_MODE = "whitelist_mode"
        const val KEY_CUSTOM_ALLOWED_NUMBERS = "custom_allowed_numbers"
        const val KEY_CUSTOM_CONTACT_NAMES = "custom_contact_names"
        const val KEY_DIAGNOSTICS_ENABLED = "diagnostics_enabled"
        const val KEY_DIRECT_DECLINE_UNAVAILABLE = "direct_decline_unavailable"
        const val KEY_CALL_CHANNEL_ID_PREFIX = "call_channel_id_"
        const val KEY_CALL_CHANNEL_DETECTED_AT_PREFIX = "call_channel_detected_at_"
        const val KEY_CALL_CHANNEL_VIBRATES_PREFIX = "call_channel_vibrates_"
        const val KEY_CALL_CHANNEL_HAS_SOUND_PREFIX = "call_channel_has_sound_"
        const val KEY_CALL_CHANNEL_IMPORTANCE_PREFIX = "call_channel_importance_"
        const val KEY_CALL_CHANNEL_LOCKSCREEN_VISIBILITY_PREFIX =
            "call_channel_lockscreen_visibility_"
    }
}
