package com.whitelistcalls

import com.whitelistcalls.externalcalls.AllowedCallAlertCoordinator
import com.whitelistcalls.externalcalls.ExternalCallProvider

/** Coordinates cancellation of the active alert that this app started for an allowed call. */
object AllowedCallVibrationController {
    private var alertCoordinator: AllowedCallAlertCoordinator? = null

    @Synchronized
    fun register(coordinator: AllowedCallAlertCoordinator) {
        if (alertCoordinator === coordinator) return
        alertCoordinator = coordinator
    }

    fun onListenerConnected(coordinator: AllowedCallAlertCoordinator) {
        register(coordinator)
    }

    @Synchronized
    fun unregister(coordinator: AllowedCallAlertCoordinator) {
        if (alertCoordinator === coordinator) alertCoordinator = null
    }

    @Synchronized
    fun cancelActiveAlert() {
        alertCoordinator?.stopAll()
    }

    @Synchronized
    fun cancelProviderAlert(provider: ExternalCallProvider, identityDigest: String? = null) {
        alertCoordinator?.stopProvider(provider, identityDigest)
    }
}
