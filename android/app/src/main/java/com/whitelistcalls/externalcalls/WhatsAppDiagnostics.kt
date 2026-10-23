package com.whitelistcalls.externalcalls

import com.whitelistcalls.SilentCallChannelStatus
import java.security.MessageDigest

data class DiagnosticsMetadata(
    val packageName: String,
    val category: String?,
    val extrasKeyNames: List<String>?,
    val callTypePresent: Boolean,
    val callTypeValue: Int?,
    val hasCallerMetadata: Boolean,
    val hasDeclineMetadata: Boolean,
    val actionCount: Int?,
    val sdkInt: Int?,
    val deviceManufacturer: String?,
    val deviceModel: String?,
    val whatsAppPackageVersion: String?,
    val personUriKind: String? = null,
    val contactCandidateCount: Int? = null,
    val callerIdentityKind: String? = null,
    val relatedPeopleCount: Int? = null,
    val relatedPeoplePhoneCount: Int? = null,
    val channelId: String? = null,
    val channelVibrates: Boolean? = null,
    val channelHasSound: Boolean? = null,
    val channelImportance: Int? = null,
    val channelLockscreenVisibility: Int? = null,
    val silentCallChannelStatus: SilentCallChannelStatus? = null,
)

data class DiagnosticsEntry(
    val eventCode: String,
    val packageName: String,
    val notificationKeyHash: String,
    val category: String?,
    val extrasKeyNames: List<String>?,
    val callTypePresent: Boolean,
    val callTypeValue: Int?,
    val hasCallerMetadata: Boolean,
    val hasDeclineMetadata: Boolean,
    val actionCount: Int?,
    val sdkInt: Int?,
    val deviceManufacturer: String?,
    val deviceModel: String?,
    val whatsAppPackageVersion: String?,
    val personUriKind: String?,
    val contactCandidateCount: Int?,
    val callerIdentityKind: String?,
    val relatedPeopleCount: Int?,
    val relatedPeoplePhoneCount: Int?,
    val channelId: String?,
    val channelVibrates: Boolean?,
    val channelHasSound: Boolean?,
    val channelImportance: Int?,
    val channelLockscreenVisibility: Int?,
    val silentCallChannelStatus: SilentCallChannelStatus?,
    val timestampMillis: Long,
    val redactedCallerValue: String?,
)

class BoundedDiagnostics(private val capacity: Int) {
    private val records = ArrayDeque<DiagnosticsEntry>()

    init {
        require(capacity >= 0) { "capacity must not be negative" }
    }

    @Synchronized
    fun record(
        eventCode: String,
        notificationKey: String,
        metadata: DiagnosticsMetadata,
        timestampMillis: Long,
        callerValue: String?,
    ) {
        if (capacity == 0) return

        records.addLast(
            DiagnosticsEntry(
                eventCode = eventCode,
                packageName = metadata.packageName,
                notificationKeyHash = hashNotificationKey(notificationKey),
                category = metadata.category,
                extrasKeyNames = metadata.extrasKeyNames?.sorted(),
                callTypePresent = metadata.callTypePresent,
                callTypeValue = metadata.callTypeValue,
                hasCallerMetadata = metadata.hasCallerMetadata,
                hasDeclineMetadata = metadata.hasDeclineMetadata,
                actionCount = metadata.actionCount,
                sdkInt = metadata.sdkInt,
                deviceManufacturer = metadata.deviceManufacturer,
                deviceModel = metadata.deviceModel,
                whatsAppPackageVersion = metadata.whatsAppPackageVersion,
                personUriKind = metadata.personUriKind,
                contactCandidateCount = metadata.contactCandidateCount,
                callerIdentityKind = metadata.callerIdentityKind,
                relatedPeopleCount = metadata.relatedPeopleCount,
                relatedPeoplePhoneCount = metadata.relatedPeoplePhoneCount,
                channelId = metadata.channelId,
                channelVibrates = metadata.channelVibrates,
                channelHasSound = metadata.channelHasSound,
                channelImportance = metadata.channelImportance,
                channelLockscreenVisibility = metadata.channelLockscreenVisibility,
                silentCallChannelStatus = metadata.silentCallChannelStatus,
                timestampMillis = timestampMillis,
                redactedCallerValue = callerValue?.let(::redactPhone),
            ),
        )

        while (records.size > capacity) records.removeFirst()
    }

    val entries: List<DiagnosticsEntry>
        @Synchronized get() = records.toList()
}

fun serializeDiagnostics(entries: List<DiagnosticsEntry>): String {
    if (entries.isEmpty()) return "WhatsApp call diagnostics\nNo diagnostic events recorded.\n"

    return buildString {
        appendLine("WhatsApp call diagnostics (redacted)")
        entries.forEach { entry ->
            appendLine(
                listOf(
                    "time=${entry.timestampMillis}",
                    "event=${entry.eventCode}",
                    "package=${exportValue(entry.packageName)}",
                    "notificationKeyHash=${entry.notificationKeyHash}",
                    "category=${exportValue(entry.category)}",
                    "extrasKeyNames=${entry.extrasKeyNames?.joinToString(",", transform = ::escapeExportValue) ?: UNAVAILABLE}",
                    "callTypePresent=${entry.callTypePresent}",
                    "callTypeValue=${entry.callTypeValue ?: UNAVAILABLE}",
                    "callerMetadata=${entry.hasCallerMetadata}",
                    "declineMetadata=${entry.hasDeclineMetadata}",
                    "actionCount=${entry.actionCount ?: UNAVAILABLE}",
                    "sdk=${entry.sdkInt ?: UNAVAILABLE}",
                    "manufacturer=${exportValue(entry.deviceManufacturer)}",
                    "model=${exportValue(entry.deviceModel)}",
                    "packageVersion=${exportValue(entry.whatsAppPackageVersion)}",
                    "personUriKind=${exportValue(entry.personUriKind)}",
                    "contactCandidateCount=${entry.contactCandidateCount ?: UNAVAILABLE}",
                    "callerIdentity=${exportValue(entry.callerIdentityKind)}",
                    "relatedPeopleCount=${entry.relatedPeopleCount ?: UNAVAILABLE}",
                    "relatedPeoplePhoneCount=${entry.relatedPeoplePhoneCount ?: UNAVAILABLE}",
                    "channelId=${exportValue(entry.channelId)}",
                    "channelVibrates=${entry.channelVibrates ?: UNAVAILABLE}",
                    "channelHasSound=${entry.channelHasSound ?: UNAVAILABLE}",
                    "channelImportance=${entry.channelImportance ?: UNAVAILABLE}",
                    "channelLockscreenVisibility=${entry.channelLockscreenVisibility ?: UNAVAILABLE}",
                    "silentCallChannelStatus=${entry.silentCallChannelStatus?.name ?: UNAVAILABLE}",
                    "caller=${exportValue(entry.redactedCallerValue)}",
                ).joinToString(" "),
            )
        }
    }
}

fun redactPhone(value: String): String =
    if (value.length <= 6) "*****" else "${value.take(4)}*****${value.takeLast(2)}"

private const val UNAVAILABLE = "unavailable"

private fun exportValue(value: String?): String =
    value?.let(::escapeExportValue) ?: UNAVAILABLE

private fun escapeExportValue(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\r", "\\r")
    .replace("\n", "\\n")
    .replace("\t", "\\t")

internal fun hashNotificationKey(notificationKey: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(notificationKey.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
