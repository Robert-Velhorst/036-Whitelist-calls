package com.whitelistcalls.externalcalls

import com.whitelistcalls.SilentCallChannelStatus
import com.whitelistcalls.ChannelAlertState
import com.whitelistcalls.observedChannelDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WhatsAppDiagnosticsTest {
    @Test
    fun observedRankingChannelPopulatesDiagnosticsFromItsAlertState() {
        val diagnostics = observedChannelDiagnostics(
            channelId = "calls-v2",
            channelState = ChannelAlertState(
                vibrates = true,
                hasSound = true,
                importance = 3,
                lockscreenVisibility = 1,
            ),
        )

        assertEquals("calls-v2", diagnostics.channelId)
        assertEquals(true, diagnostics.channelVibrates)
        assertEquals(true, diagnostics.channelHasSound)
        assertEquals(3, diagnostics.channelImportance)
        assertEquals(1, diagnostics.channelLockscreenVisibility)
        assertEquals(SilentCallChannelStatus.INCOMPLETE, diagnostics.silentCallChannelStatus)
    }

    @Test
    fun noRankingChannelProducesUnknownStatusAndUnavailableChannelFields() {
        val diagnostics = observedChannelDiagnostics(channelId = null, channelState = null)

        assertEquals(null, diagnostics.channelId)
        assertEquals(null, diagnostics.channelVibrates)
        assertEquals(null, diagnostics.channelHasSound)
        assertEquals(null, diagnostics.channelImportance)
        assertEquals(null, diagnostics.channelLockscreenVisibility)
        assertEquals(SilentCallChannelStatus.UNKNOWN, diagnostics.silentCallChannelStatus)
    }

    @Test
    fun redactsPhoneNumbers() = assertEquals("+316*****78", redactPhone("+31612345678"))

    @Test
    fun recordsOnlyHashedAndRedactedSensitiveValues() {
        val diagnostics = BoundedDiagnostics(capacity = 2)

        diagnostics.record(
            eventCode = "WA_DIRECT_REJECT_FAILED",
            notificationKey = "raw-notification-key",
            metadata = completeMetadata(),
            timestampMillis = 1234,
            callerValue = "+31612345678",
        )

        val entry = diagnostics.entries.single()
        assertEquals("WA_DIRECT_REJECT_FAILED", entry.eventCode)
        assertEquals("com.whatsapp", entry.packageName)
        assertNotEquals("raw-notification-key", entry.notificationKeyHash)
        assertEquals("call", entry.category)
        assertEquals(
            listOf("android.callPerson", "android.callType", "android.title"),
            entry.extrasKeyNames,
        )
        assertEquals(true, entry.callTypePresent)
        assertEquals(1, entry.callTypeValue)
        assertEquals(true, entry.hasCallerMetadata)
        assertEquals(true, entry.hasDeclineMetadata)
        assertEquals(2, entry.actionCount)
        assertEquals(35, entry.sdkInt)
        assertEquals("Google", entry.deviceManufacturer)
        assertEquals("Pixel 9", entry.deviceModel)
        assertEquals("2.26.17", entry.whatsAppPackageVersion)
        assertEquals(1234, entry.timestampMillis)
        assertEquals("+316*****78", entry.redactedCallerValue)
    }

    @Test
    fun retainsOnlyTheNewestRecordsWithinItsCapacity() {
        val diagnostics = BoundedDiagnostics(capacity = 2)

        diagnostics.record("first", "key-1", completeMetadata(), 1, null)
        diagnostics.record("second", "key-2", completeMetadata(), 2, null)
        diagnostics.record("third", "key-3", completeMetadata(), 3, null)

        assertEquals(listOf("second", "third"), diagnostics.entries.map { it.eventCode })
    }

    @Test
    fun zeroCapacityRetainsNoDiagnosticRecords() {
        val diagnostics = BoundedDiagnostics(capacity = 0)

        diagnostics.record("observed", "key", completeMetadata(), 1, null)

        assertTrue(diagnostics.entries.isEmpty())
    }

    @Test
    fun exportSerializesOnlyTheRedactedSchemaAndMarksUnavailableValues() {
        val diagnostics = BoundedDiagnostics(capacity = 2)
        diagnostics.record(
            eventCode = "WA_NOTIFICATION_OBSERVED",
            notificationKey = "raw-private-key",
            metadata = completeMetadata(),
            timestampMillis = 1_234L,
            callerValue = "+31612345678",
        )
        diagnostics.record(
            eventCode = "WA_NOTIFICATION_OBSERVED",
            notificationKey = "second-private-key",
            metadata = DiagnosticsMetadata(
                packageName = "com.whatsapp.w4b",
                category = null,
                extrasKeyNames = null,
                callTypePresent = false,
                callTypeValue = null,
                hasCallerMetadata = false,
                hasDeclineMetadata = false,
                actionCount = null,
                sdkInt = 31,
                deviceManufacturer = null,
                deviceModel = null,
                whatsAppPackageVersion = null,
            ),
            timestampMillis = 2_345L,
            callerValue = null,
        )

        val export = serializeDiagnostics(diagnostics.entries)

        assertTrue(export.contains("package=com.whatsapp"))
        assertTrue(export.contains("extrasKeyNames=android.callPerson,android.callType,android.title"))
        assertTrue(export.contains("callTypePresent=true"))
        assertTrue(export.contains("callTypeValue=1"))
        assertTrue(export.contains("callerMetadata=true"))
        assertTrue(export.contains("declineMetadata=true"))
        assertTrue(export.contains("actionCount=2"))
        assertTrue(export.contains("sdk=35"))
        assertTrue(export.contains("manufacturer=Google"))
        assertTrue(export.contains("model=Pixel 9"))
        assertTrue(export.contains("packageVersion=2.26.17"))
        assertTrue(export.contains("caller=+316*****78"))
        assertTrue(export.contains("category=unavailable"))
        assertTrue(export.contains("extrasKeyNames=unavailable"))
        assertTrue(export.contains("callTypeValue=unavailable"))
        assertTrue(export.contains("actionCount=unavailable"))
        assertTrue(export.contains("packageVersion=unavailable"))
        assertFalse(export.contains("raw-private-key"))
        assertFalse(export.contains("second-private-key"))
        assertFalse(export.contains("+31612345678"))
        assertFalse(export.contains("Confidential notification title"))
        assertFalse(export.contains("Confidential notification text"))
        assertFalse(export.contains("notificationPayload"))
    }

    @Test
    fun exportIncludesOnlySafeCallerResolutionDetails() {
        val diagnostics = BoundedDiagnostics(capacity = 1)
        diagnostics.record(
            eventCode = "WA_NOTIFICATION_OBSERVED",
            notificationKey = "private-key",
            metadata = completeMetadata().copy(
                personUriKind = "contacts_lookup",
                contactCandidateCount = 1,
                callerIdentityKind = "phone",
            ),
            timestampMillis = 1L,
            callerValue = "+31612345678",
        )

        val export = serializeDiagnostics(diagnostics.entries)

        assertTrue(export.contains("personUriKind=contacts_lookup"))
        assertTrue(export.contains("contactCandidateCount=1"))
        assertTrue(export.contains("callerIdentity=phone"))
        assertFalse(export.contains("private-key"))
        assertFalse(export.contains("+31612345678"))
    }

    @Test
    fun exportIncludesRuntimeChannelCompatibilityMetadataWithoutCallerSecrets() {
        val diagnostics = BoundedDiagnostics(capacity = 1)
        diagnostics.record(
            eventCode = "WA_NOTIFICATION_OBSERVED",
            notificationKey = "private-key",
            metadata = completeMetadata().copy(
                channelId = "calls-v2",
                channelVibrates = true,
                channelHasSound = true,
                channelImportance = 3,
                channelLockscreenVisibility = 1,
                silentCallChannelStatus = SilentCallChannelStatus.INCOMPLETE,
            ),
            timestampMillis = 1L,
            callerValue = "+31612345678",
        )

        val export = serializeDiagnostics(diagnostics.entries)

        assertTrue(export.contains("channelId=calls-v2"))
        assertTrue(export.contains("channelVibrates=true"))
        assertTrue(export.contains("channelHasSound=true"))
        assertTrue(export.contains("channelImportance=3"))
        assertTrue(export.contains("channelLockscreenVisibility=1"))
        assertTrue(export.contains("silentCallChannelStatus=INCOMPLETE"))
        assertFalse(export.contains("Confidential caller display name"))
        assertFalse(export.contains("+31612345678"))
    }

    private fun completeMetadata() = DiagnosticsMetadata(
        packageName = "com.whatsapp",
        category = "call",
        extrasKeyNames = listOf("android.callPerson", "android.callType", "android.title"),
        callTypePresent = true,
        callTypeValue = 1,
        hasCallerMetadata = true,
        hasDeclineMetadata = true,
        actionCount = 2,
        sdkInt = 35,
        deviceManufacturer = "Google",
        deviceModel = "Pixel 9",
        whatsAppPackageVersion = "2.26.17",
    )
}
