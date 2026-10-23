package com.whitelistcalls.externalcalls

import android.content.SharedPreferences
import com.whitelistcalls.AllowedCallVibrator
import com.whitelistcalls.WeChatDiagnosticStatus
import com.whitelistcalls.WeChatSettingsStore
import com.whitelistcalls.WhatsAppAllowedAlertController
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatCallNotificationHandlingTest {
    private val vibrator = RecordingVibrator()
    private val coordinator = AllowedCallAlertCoordinator(vibrator)
    private val lookedUpDigests = mutableListOf<String>()
    private val handler = WeChatCallNotificationHandler(
        isAllowedDigest = { digest ->
            lookedUpDigests += digest
            digest == VALID_DIGEST
        },
        allowedCallAlertCoordinator = coordinator,
    )

    @Test
    fun android14RequiresAnExplicitIncomingCallType() {
        assertTrue(isIncomingWeChatCall(34, 1, false, false))
        assertFalse(isIncomingWeChatCall(34, 2, true, true))
        assertFalse(isIncomingWeChatCall(34, 3, true, true))
        assertFalse(isIncomingWeChatCall(34, null, true, true))
    }

    @Test
    fun android12And13RequireBothPersonAndDeclineMetadata() {
        assertTrue(isIncomingWeChatCall(31, null, true, true))
        assertTrue(isIncomingWeChatCall(33, null, true, true))
        assertFalse(isIncomingWeChatCall(33, null, false, true))
        assertFalse(isIncomingWeChatCall(33, null, true, false))
        assertFalse(isIncomingWeChatCall(33, 1, false, false))
        assertFalse(isIncomingWeChatCall(30, null, true, true))
        assertFalse(isIncomingWeChatCall(33, 2, true, true))
    }

    @Test
    fun diagnosticStateTracksLatestIdentityWithoutWritingPreferences() {
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ -> error("Diagnostics must not touch preferences: ${method.name}") }
            as SharedPreferences
        val store = WeChatSettingsStore(preferences)
        store.recordCandidate(WeChatIdentityCandidate.Supported(VALID_DIGEST), 1L)
        assertEquals(WeChatDiagnosticStatus.SUPPORTED, store.diagnosticStatus())
        store.recordCandidate(WeChatIdentityCandidate.NameOnly, 2L)
        assertEquals(WeChatDiagnosticStatus.NAME_ONLY, store.diagnosticStatus())
        store.recordCandidate(WeChatIdentityCandidate.Missing, 3L)
        assertEquals(WeChatDiagnosticStatus.MISSING, store.diagnosticStatus())
        store.recordUnsupportedNotification()
        assertEquals(WeChatDiagnosticStatus.UNSUPPORTED, store.diagnosticStatus())
        store.setDirectDeclineUnavailable(true)
        assertTrue(WeChatSettingsStore(preferences).isDirectDeclineUnavailable())
        store.setDirectDeclineUnavailable(false)
        assertFalse(WeChatSettingsStore(preferences).isDirectDeclineUnavailable())
    }

    @Test
    fun notificationPhoneValuesCannotBecomeWeChatWhitelistIdentity() {
        assertEquals(
            WeChatIdentityCandidate.NameOnly,
            weChatNotificationIdentity(
                WeChatIdentityObservation(
                    personUri = "tel:+15551234567",
                    personKey = "+1 (555) 123-4567",
                    shortcutId = "15551234567",
                    displayName = "Alice",
                ),
            ),
        )
    }

    @Test
    fun opaqueShortcutStillWorksWhenPersonUriContainsOnlyAPhoneNumber() {
        assertEquals(
            WeChatCallerIdentity.extract(WeChatIdentityObservation(shortcutId = "opaque-contact-a")),
            weChatNotificationIdentity(
                WeChatIdentityObservation(personUri = "tel:+15551234567", shortcutId = "opaque-contact-a"),
            ),
        )
    }

    @Test
    fun nameOnlyWeChatIncomingCallIsBlockedWhenFilteringIsEnabled() {
        assertEquals(
            WeChatHandlingOutcome.BLOCKED,
            handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.NameOnly, true),
        )
        assertEquals(emptyList<String>(), vibrator.startedKeys)
        assertEquals(emptyList<String>(), lookedUpDigests)
    }

    @Test
    fun missingWeChatIdentityIsBlockedWhenFilteringIsEnabled() {
        assertEquals(
            WeChatHandlingOutcome.BLOCKED,
            handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.Missing, true),
        )
        assertEquals(emptyList<String>(), vibrator.startedKeys)
        assertEquals(emptyList<String>(), lookedUpDigests)
    }

    @Test
    fun allIdentityKindsAreDiagnosticOnlyWhenFilteringIsOff() {
        listOf(
            WeChatIdentityCandidate.NameOnly,
            WeChatIdentityCandidate.Missing,
            WeChatIdentityCandidate.Supported(VALID_DIGEST),
        ).forEach { identity ->
            assertEquals(
                WeChatHandlingOutcome.DIAGNOSTIC_ONLY,
                handler.onIncoming("wechat-call-1", identity, false),
            )
        }
        assertEquals(emptyList<String>(), vibrator.startedKeys)
        assertEquals(emptyList<String>(), lookedUpDigests)
    }

    @Test
    fun unboundSupportedDigestIsBlocked() {
        assertEquals(
            WeChatHandlingOutcome.BLOCKED,
            handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.Supported(OTHER_DIGEST), true),
        )
        assertEquals(listOf(OTHER_DIGEST), lookedUpDigests)
        assertEquals(emptyList<String>(), vibrator.startedKeys)
    }

    @Test
    fun allowedWeChatDigestStartsAlertAndRemovalStopsIt() {
        assertEquals(
            WeChatHandlingOutcome.ALLOWED,
            handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.Supported(VALID_DIGEST), true),
        )
        handler.onNotificationRemoved("wechat-call-1")
        assertEquals(listOf(VALID_DIGEST), lookedUpDigests)
        assertEquals(listOf("wechat-call-1"), vibrator.startedKeys)
        assertEquals(listOf("wechat-call-1"), vibrator.stoppedKeys)
    }

    @Test
    fun duplicateIncomingUpdatesDoNotRestartAnAllowedAlert() {
        repeat(2) {
            handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.Supported(VALID_DIGEST), true)
        }
        assertEquals(listOf("wechat-call-1"), vibrator.startedKeys)
        assertEquals(emptyList<String>(), vibrator.stoppedKeys)
    }

    @Test
    fun blockedUpdateStopsThePreviouslyAllowedAlertForThatNotification() {
        handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.Supported(VALID_DIGEST), true)
        handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.NameOnly, true)
        assertEquals(listOf("wechat-call-1"), vibrator.stoppedKeys)
    }

    @Test
    fun filteringDisabledUpdateStopsThatNotificationsAlert() {
        handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.Supported(VALID_DIGEST), true)
        handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.Supported(VALID_DIGEST), false)
        assertEquals(listOf("wechat-call-1"), vibrator.stoppedKeys)
    }

    @Test
    fun unrelatedDiagnosticAndRemovalDoNotStopWhatsAppAlert() {
        coordinator.onAllowedIncoming("whatsapp-call")
        handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.NameOnly, false)
        handler.onNotificationRemoved("wechat-call-1")
        assertEquals(emptyList<String>(), vibrator.stoppedKeys)
    }

    @Test
    fun disabledWhatsAppNotificationDoesNotStopAllowedWeChatAlert() {
        val whatsAppAlerts = WhatsAppAllowedAlertController(coordinator)
        whatsAppAlerts.onHandlingResult("whatsapp-call", NotificationHandlingResult.ALLOWED)
        handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.Supported(VALID_DIGEST), true)

        whatsAppAlerts.onFilteringDisabled(masterEnabled = true)

        assertEquals("wechat-call-1", vibrator.activeNotificationKey)
        assertEquals(0, vibrator.stopAllCount)
        assertEquals(listOf("whatsapp-call"), vibrator.stoppedKeys)
    }

    @Test
    fun disabledWhatsAppFilteringStopsItsOwnAllowedAlert() {
        val whatsAppAlerts = WhatsAppAllowedAlertController(coordinator)
        whatsAppAlerts.onHandlingResult("whatsapp-call", NotificationHandlingResult.ALLOWED)

        whatsAppAlerts.onFilteringDisabled(masterEnabled = true)

        assertEquals(null, vibrator.activeNotificationKey)
        assertEquals(listOf("whatsapp-call"), vibrator.stoppedKeys)
    }

    @Test
    fun masterProtectionOffStillStopsAllowedWeChatAlert() {
        val whatsAppAlerts = WhatsAppAllowedAlertController(coordinator)
        handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.Supported(VALID_DIGEST), true)

        whatsAppAlerts.onFilteringDisabled(masterEnabled = false)

        assertEquals(null, vibrator.activeNotificationKey)
        assertEquals(1, vibrator.stopAllCount)
    }

    @Test
    fun irrelevantWhatsAppNotificationDoesNotStopAllowedWeChatAlert() {
        val whatsAppAlerts = WhatsAppAllowedAlertController(coordinator)
        handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.Supported(VALID_DIGEST), true)

        whatsAppAlerts.onHandlingResult("whatsapp-message", NotificationHandlingResult.IGNORED)

        assertEquals("wechat-call-1", vibrator.activeNotificationKey)
        assertEquals(0, vibrator.stopAllCount)
    }

    @Test
    fun rejectedWhatsAppCallDoesNotStopAllowedWeChatAlert() {
        val whatsAppAlerts = WhatsAppAllowedAlertController(coordinator)
        whatsAppAlerts.onHandlingResult("whatsapp-call", NotificationHandlingResult.ALLOWED)
        handler.onIncoming("wechat-call-1", WeChatIdentityCandidate.Supported(VALID_DIGEST), true)

        whatsAppAlerts.onHandlingResult("whatsapp-blocked", NotificationHandlingResult.DECLINED)

        assertEquals("wechat-call-1", vibrator.activeNotificationKey)
        assertEquals(listOf("whatsapp-call"), vibrator.stoppedKeys)
    }

    @Test
    fun rejectedWhatsAppCallStopsItsOwnPreviousAlert() {
        val whatsAppAlerts = WhatsAppAllowedAlertController(coordinator)
        whatsAppAlerts.onHandlingResult("whatsapp-call", NotificationHandlingResult.ALLOWED)

        whatsAppAlerts.onHandlingResult("whatsapp-blocked", NotificationHandlingResult.DECLINED)

        assertEquals(null, vibrator.activeNotificationKey)
        assertEquals(listOf("whatsapp-call"), vibrator.stoppedKeys)
    }

    private class RecordingVibrator : AllowedCallVibrator {
        val startedKeys = mutableListOf<String>()
        val stoppedKeys = mutableListOf<String>()
        var activeNotificationKey: String? = null
        var stopAllCount = 0
        override fun start(notificationKey: String) {
            startedKeys += notificationKey
            activeNotificationKey = notificationKey
        }
        override fun stop(notificationKey: String) {
            stoppedKeys += notificationKey
            if (activeNotificationKey == notificationKey) activeNotificationKey = null
        }
        override fun stopAll() {
            stopAllCount++
            activeNotificationKey = null
        }
    }

    private companion object {
        val VALID_DIGEST = "a".repeat(64)
        val OTHER_DIGEST = "b".repeat(64)
    }
}
