package com.whitelistcalls.externalcalls

import com.whitelistcalls.AllowedCallVibrator
import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppCallAlertHandlerTest {
    @Test
    fun newRejectedIncomingStopsTheFormerAllowedAlert() {
        val vibrator = RecordingVibrator()
        val handler = WhatsAppCallAlertHandler(AllowedCallAlertCoordinator(vibrator))
        handler.onHandlingResult("allowed", NotificationHandlingResult.ALLOWED)

        handler.onHandlingResult("blocked", NotificationHandlingResult.DECLINED)

        assertEquals(listOf("allowed"), vibrator.stoppedKeys)
    }

    @Test
    fun unrelatedIgnoredUpdateDoesNotStopTheActiveAllowedAlert() {
        val vibrator = RecordingVibrator()
        val handler = WhatsAppCallAlertHandler(AllowedCallAlertCoordinator(vibrator))
        handler.onHandlingResult("allowed", NotificationHandlingResult.ALLOWED)

        handler.onHandlingResult("unrelated", NotificationHandlingResult.IGNORED)

        assertEquals(emptyList<String>(), vibrator.stoppedKeys)
    }

    @Test
    fun allowedStartsOneAlertAndEveryOtherResultEndsTheMatchingAlert() {
        val vibrator = RecordingVibrator()
        val handler = WhatsAppCallAlertHandler(AllowedCallAlertCoordinator(vibrator))

        handler.onHandlingResult("active", NotificationHandlingResult.ALLOWED)
        handler.onHandlingResult("active", NotificationHandlingResult.ALLOWED)

        assertEquals(listOf("active"), vibrator.startedKeys)

        listOf(
            NotificationHandlingResult.IGNORED,
            NotificationHandlingResult.NO_DIRECT_DECLINE,
            NotificationHandlingResult.DUPLICATE,
            NotificationHandlingResult.DECLINED,
            NotificationHandlingResult.DECLINE_FAILED,
        ).forEach { result ->
            handler.onHandlingResult("active", result)
            handler.onHandlingResult("active", NotificationHandlingResult.ALLOWED)
        }

        assertEquals(
            listOf("active", "active", "active", "active", "active", "active"),
            vibrator.startedKeys,
        )
        assertEquals(
            listOf("active", "active", "active", "active", "active"),
            vibrator.stoppedKeys,
        )
    }

    private class RecordingVibrator : AllowedCallVibrator {
        val startedKeys = mutableListOf<String>()
        val stoppedKeys = mutableListOf<String>()

        override fun start(notificationKey: String) {
            startedKeys += notificationKey
        }

        override fun stop(notificationKey: String) {
            stoppedKeys += notificationKey
        }

        override fun stopAll() = Unit
    }
}
