package com.whitelistcalls.externalcalls

import com.whitelistcalls.AllowedCallVibrator
import org.junit.Assert.assertEquals
import org.junit.Test

class AllowedCallAlertCoordinatorTest {
    @Test
    fun duplicateIncomingForActiveKeyDoesNotStartAnotherVibration() {
        val vibrator = RecordingVibrator()
        val coordinator = AllowedCallAlertCoordinator(vibrator)

        coordinator.onAllowedIncoming("first")
        coordinator.onAllowedIncoming("first")

        assertEquals(listOf("first"), vibrator.startedKeys)
        assertEquals(emptyList<String>(), vibrator.stoppedKeys)
    }

    @Test
    fun newIncomingStopsFormerKeyBeforeStartingNewKey() {
        val vibrator = RecordingVibrator()
        val coordinator = AllowedCallAlertCoordinator(vibrator)

        coordinator.onAllowedIncoming("first")
        coordinator.onAllowedIncoming("second")

        assertEquals(listOf("start:first", "stop:first", "start:second"), vibrator.events)
    }

    @Test
    fun removalStopsOnlyMatchingActiveKey() {
        val vibrator = RecordingVibrator()
        val coordinator = AllowedCallAlertCoordinator(vibrator)
        coordinator.onAllowedIncoming("active")

        coordinator.onNotificationRemoved("other")
        coordinator.onNotificationRemoved("active")

        assertEquals(listOf("active"), vibrator.stoppedKeys)
        coordinator.onAllowedIncoming("active")
        assertEquals(listOf("active", "active"), vibrator.startedKeys)
    }

    @Test
    fun incomingTransitionEndedStopsOnlyMatchingActiveKey() {
        val vibrator = RecordingVibrator()
        val coordinator = AllowedCallAlertCoordinator(vibrator)
        coordinator.onAllowedIncoming("active")

        coordinator.onIncomingTransitionEnded("other")
        coordinator.onIncomingTransitionEnded("active")

        assertEquals(listOf("active"), vibrator.stoppedKeys)
        coordinator.onAllowedIncoming("active")
        assertEquals(listOf("active", "active"), vibrator.startedKeys)
    }

    @Test
    fun rejectedIncomingSessionStopsAnyActiveAllowedAlert() {
        val vibrator = RecordingVibrator()
        val coordinator = AllowedCallAlertCoordinator(vibrator)
        coordinator.onAllowedIncoming("allowed")

        coordinator.onIncomingSessionRejected()

        assertEquals(listOf("allowed"), vibrator.stoppedKeys)
        coordinator.onAllowedIncoming("next-allowed")
        assertEquals(listOf("allowed", "next-allowed"), vibrator.startedKeys)
    }

    @Test
    fun stopAllClearsActiveKeySoLaterIncomingCanStart() {
        val vibrator = RecordingVibrator()
        val coordinator = AllowedCallAlertCoordinator(vibrator)
        coordinator.onAllowedIncoming("active")

        coordinator.stopAll()
        coordinator.onAllowedIncoming("active")

        assertEquals(1, vibrator.stopAllCalls)
        assertEquals(listOf("active", "active"), vibrator.startedKeys)
    }

    private class RecordingVibrator : AllowedCallVibrator {
        val events = mutableListOf<String>()
        val startedKeys = mutableListOf<String>()
        val stoppedKeys = mutableListOf<String>()
        var stopAllCalls = 0

        override fun start(notificationKey: String) {
            events += "start:$notificationKey"
            startedKeys += notificationKey
        }

        override fun stop(notificationKey: String) {
            events += "stop:$notificationKey"
            stoppedKeys += notificationKey
        }

        override fun stopAll() {
            events += "stopAll"
            stopAllCalls += 1
        }
    }
}
