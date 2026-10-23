package com.whitelistcalls.externalcalls

import com.whitelistcalls.ExternalCallChannelInfo
import com.whitelistcalls.confirmedIncomingChannelInfo
import com.whitelistcalls.isWhatsAppFilteringEffective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WhatsAppCallNotificationCoordinatorTest {
    @Test
    fun confirmedIncomingChannelInfoAcceptsOnlySupportedIncomingCallsWithAChannel() {
        assertEquals(
            ExternalCallChannelInfo("com.whatsapp", "calls-personal", 1_000L),
            confirmedIncomingChannelInfo(
                packageName = "com.whatsapp",
                callType = CallType.INCOMING,
                channelId = "calls-personal",
                detectedAtMillis = 1_000L,
            ),
        )
        assertEquals(
            ExternalCallChannelInfo("com.whatsapp.w4b", "calls-business", 2_000L),
            confirmedIncomingChannelInfo(
                packageName = "com.whatsapp.w4b",
                callType = CallType.INCOMING,
                channelId = "calls-business",
                detectedAtMillis = 2_000L,
            ),
        )
        assertEquals(
            null,
            confirmedIncomingChannelInfo(
                packageName = "com.whatsapp",
                callType = CallType.ONGOING,
                channelId = "calls-personal",
                detectedAtMillis = 3_000L,
            ),
        )
        assertEquals(
            null,
            confirmedIncomingChannelInfo(
                packageName = "org.example.chat",
                callType = CallType.INCOMING,
                channelId = "calls-unsupported",
                detectedAtMillis = 4_000L,
            ),
        )
        assertEquals(
            null,
            confirmedIncomingChannelInfo(
                packageName = "com.whatsapp",
                callType = CallType.INCOMING,
                channelId = "   ",
                detectedAtMillis = 5_000L,
            ),
        )
    }

    @Test
    fun unsupportedPackageReturnsBeforePolicyOrSend() {
        val coordinator = coordinator()
        var filteringChecked = false
        var sendAttempts = 0

        val result = coordinator.onNotificationPosted(
            snapshot = incomingSnapshot(packageName = "org.example.chat"),
            notificationKey = "unsupported-key",
            hasCallerMetadata = true,
            filteringEnabled = { filteringChecked = true; true },
            isAllowed = { error("unsupported packages must not reach the whitelist") },
            nowMillis = 1_000L,
            sendDirectDecline = { sendAttempts += 1; DirectDeclineSendResult.SENT },
        )

        assertEquals(NotificationHandlingResult.IGNORED, result)
        assertFalse(filteringChecked)
        assertEquals(0, sendAttempts)
    }

    @Test
    fun allowedCallerHasZeroSendAttempts() {
        val coordinator = coordinator()
        var sendAttempts = 0

        val result = coordinator.onNotificationPosted(
            snapshot = incomingSnapshot(),
            notificationKey = "allowed-key",
            hasCallerMetadata = true,
            filteringEnabled = { true },
            isAllowed = { it == "+31612345678" },
            nowMillis = 2_000L,
            sendDirectDecline = { sendAttempts += 1; DirectDeclineSendResult.SENT },
        )

        assertEquals(NotificationHandlingResult.ALLOWED, result)
        assertEquals(0, sendAttempts)
    }

    @Test
    fun disabledFilteringDoesNotInvokeWhitelistOrSendCallbacks() {
        val coordinator = coordinator()
        var whitelistChecks = 0
        var sendAttempts = 0

        val result = coordinator.onNotificationPosted(
            snapshot = incomingSnapshot(),
            notificationKey = "disabled-key",
            hasCallerMetadata = true,
            filteringEnabled = { false },
            isAllowed = { whitelistChecks += 1; true },
            nowMillis = 2_500L,
            sendDirectDecline = { sendAttempts += 1; DirectDeclineSendResult.SENT },
        )

        assertEquals(NotificationHandlingResult.ALLOWED, result)
        assertEquals(0, whitelistChecks)
        assertEquals(0, sendAttempts)
    }

    @Test
    fun masterOffNeverConsultsWhitelistOrSends() {
        val coordinator = coordinator()
        var whitelistChecks = 0
        var sendAttempts = 0

        val result = coordinator.onNotificationPosted(
            snapshot = incomingSnapshot(),
            notificationKey = "master-off-key",
            hasCallerMetadata = true,
            filteringEnabled = {
                isWhatsAppFilteringEffective(masterEnabled = false, whatsappEnabled = true)
            },
            isAllowed = { whitelistChecks += 1; false },
            nowMillis = 2_600L,
            sendDirectDecline = { sendAttempts += 1; DirectDeclineSendResult.SENT },
        )

        assertEquals(NotificationHandlingResult.ALLOWED, result)
        assertEquals(0, whitelistChecks)
        assertEquals(0, sendAttempts)
    }

    @Test
    fun masterOnAndSubToggleOffNeverConsultsWhitelistOrSends() {
        val coordinator = coordinator()
        var whitelistChecks = 0
        var sendAttempts = 0

        val result = coordinator.onNotificationPosted(
            snapshot = incomingSnapshot(),
            notificationKey = "sub-toggle-off-key",
            hasCallerMetadata = true,
            filteringEnabled = {
                isWhatsAppFilteringEffective(masterEnabled = true, whatsappEnabled = false)
            },
            isAllowed = { whitelistChecks += 1; false },
            nowMillis = 2_700L,
            sendDirectDecline = { sendAttempts += 1; DirectDeclineSendResult.SENT },
        )

        assertEquals(NotificationHandlingResult.ALLOWED, result)
        assertEquals(0, whitelistChecks)
        assertEquals(0, sendAttempts)
    }

    @Test
    fun rejectionSendsOnlyAnExplicitDirectDeclineOnce() {
        val coordinator = coordinator()
        var sendAttempts = 0
        val send = { sendAttempts += 1; DirectDeclineSendResult.SENT }

        val first = coordinator.onNotificationPosted(
            snapshot = incomingSnapshot(),
            notificationKey = "blocked-key",
            hasCallerMetadata = true,
            filteringEnabled = { true },
            isAllowed = { false },
            nowMillis = 3_000L,
            sendDirectDecline = send,
        )
        val repeated = coordinator.onNotificationPosted(
            snapshot = incomingSnapshot(),
            notificationKey = "blocked-key",
            hasCallerMetadata = true,
            filteringEnabled = { true },
            isAllowed = { false },
            nowMillis = 3_001L,
            sendDirectDecline = send,
        )

        assertEquals(NotificationHandlingResult.DECLINED, first)
        assertEquals(NotificationHandlingResult.DUPLICATE, repeated)
        assertEquals(1, sendAttempts)
    }

    @Test
    fun rejectionWithoutExplicitDeclineMetadataNeverInvokesCallback() {
        val coordinator = coordinator()
        var sendAttempts = 0

        val result = coordinator.onNotificationPosted(
            snapshot = incomingSnapshot(hasDirectDeclineIntent = false),
            notificationKey = "no-direct-decline-key",
            hasCallerMetadata = true,
            filteringEnabled = { true },
            isAllowed = { false },
            nowMillis = 4_000L,
            sendDirectDecline = { sendAttempts += 1; DirectDeclineSendResult.SENT },
        )

        assertEquals(NotificationHandlingResult.NO_DIRECT_DECLINE, result)
        assertEquals(0, sendAttempts)
    }

    @Test
    fun policyRunsBeforeDedupeSoAnAllowedUpdateDoesNotConsumeTheSession() {
        val coordinator = coordinator()
        var allowed = true
        var sendAttempts = 0
        val snapshot = incomingSnapshot()

        assertEquals(
            NotificationHandlingResult.ALLOWED,
            coordinator.onNotificationPosted(
                snapshot = snapshot,
                notificationKey = "policy-first-key",
                hasCallerMetadata = true,
                filteringEnabled = { true },
                isAllowed = { allowed },
                nowMillis = 5_000L,
                sendDirectDecline = { sendAttempts += 1; DirectDeclineSendResult.SENT },
            ),
        )
        allowed = false
        assertEquals(
            NotificationHandlingResult.DECLINED,
            coordinator.onNotificationPosted(
                snapshot = snapshot,
                notificationKey = "policy-first-key",
                hasCallerMetadata = true,
                filteringEnabled = { true },
                isAllowed = { allowed },
                nowMillis = 5_001L,
                sendDirectDecline = { sendAttempts += 1; DirectDeclineSendResult.SENT },
            ),
        )
        assertEquals(1, sendAttempts)
    }

    @Test
    fun removalAllowsOneFreshAttempt() {
        val coordinator = coordinator()
        var sendAttempts = 0
        val send = { sendAttempts += 1; DirectDeclineSendResult.SENT }

        coordinator.onNotificationPosted(
            incomingSnapshot(), "removal-key", true, { true }, { false }, 6_000L, send,
        )
        coordinator.onNotificationRemoved("com.whatsapp", "removal-key")
        val afterRemoval = coordinator.onNotificationPosted(
            incomingSnapshot(), "removal-key", true, { true }, { false }, 6_001L, send,
        )

        assertEquals(NotificationHandlingResult.DECLINED, afterRemoval)
        assertEquals(2, sendAttempts)
    }

    @Test
    fun canceledSendIsDiagnosedWithoutSecretsAndIsNotRetried() {
        val diagnostics = BoundedDiagnostics(capacity = 10)
        val coordinator = WhatsAppCallNotificationCoordinator(BlockedCallSessionStore(), diagnostics)
        var sendAttempts = 0
        val canceledSend = {
            sendAttempts += 1
            DirectDeclineSendResult.CANCELED
        }

        val first = coordinator.onNotificationPosted(
            incomingSnapshot(), "private-notification-key", true,
            { true }, { false }, 7_000L, canceledSend,
        )
        val repeated = coordinator.onNotificationPosted(
            incomingSnapshot(), "private-notification-key", true,
            { true }, { false }, 7_001L, canceledSend,
        )

        assertEquals(NotificationHandlingResult.DECLINE_FAILED, first)
        assertEquals(NotificationHandlingResult.DUPLICATE, repeated)
        assertEquals(1, sendAttempts)
        assertEquals(1, diagnostics.entries.size)
        with(diagnostics.entries.single()) {
            assertEquals("WA_DIRECT_REJECT_FAILED", eventCode)
            assertEquals("+316*****78", redactedCallerValue)
            assertNotEquals("private-notification-key", notificationKeyHash)
            assertFalse(notificationKeyHash.contains("private-notification-key"))
        }
    }

    private fun coordinator() = WhatsAppCallNotificationCoordinator(
        sessions = BlockedCallSessionStore(),
        diagnostics = BoundedDiagnostics(capacity = 10),
    )

    private fun incomingSnapshot(
        packageName: String = "com.whatsapp",
        hasDirectDeclineIntent: Boolean = true,
    ) = NotificationSnapshot(
        packageName = packageName,
        callType = CallType.INCOMING,
        callerIdentity = CallerIdentity.Phone("+31612345678"),
        hasDirectDeclineIntent = hasDirectDeclineIntent,
    )
}
