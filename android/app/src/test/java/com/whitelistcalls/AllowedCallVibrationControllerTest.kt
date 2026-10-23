package com.whitelistcalls

import com.whitelistcalls.externalcalls.AllowedCallAlertCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test

class AllowedCallVibrationControllerTest {
    @Test
    fun cancellationStopsTheActiveAppOwnedAllowedCallAlertImmediately() {
        val vibrator = RecordingVibrator()
        val coordinator = AllowedCallAlertCoordinator(vibrator)
        AllowedCallVibrationController.register(coordinator)

        try {
            coordinator.onAllowedIncoming("allowed-call")

            AllowedCallVibrationController.cancelActiveAlert()

            assertEquals(listOf("start:allowed-call", "stopAll"), vibrator.events)
        } finally {
            AllowedCallVibrationController.unregister(coordinator)
        }
    }

    @Test
    fun listenerReconnectRestoresToggleCancellationForTheSameCoordinator() {
        val vibrator = RecordingVibrator()
        val coordinator = AllowedCallAlertCoordinator(vibrator)
        AllowedCallVibrationController.register(coordinator)

        try {
            AllowedCallVibrationController.unregister(coordinator)
            coordinator.onAllowedIncoming("allowed-call")

            AllowedCallVibrationController.onListenerConnected(coordinator)
            AllowedCallVibrationController.cancelActiveAlert()

            assertEquals(listOf("start:allowed-call", "stopAll"), vibrator.events)
        } finally {
            AllowedCallVibrationController.unregister(coordinator)
        }
    }

    private class RecordingVibrator : AllowedCallVibrator {
        val events = mutableListOf<String>()

        override fun start(notificationKey: String) {
            events += "start:$notificationKey"
        }

        override fun stop(notificationKey: String) {
            events += "stop:$notificationKey"
        }

        override fun stopAll() {
            events += "stopAll"
        }
    }
}
