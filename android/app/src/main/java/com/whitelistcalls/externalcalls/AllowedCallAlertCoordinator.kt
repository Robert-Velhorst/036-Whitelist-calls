package com.whitelistcalls.externalcalls

import com.whitelistcalls.AllowedCallVibrator

class AllowedCallAlertCoordinator(
    private val vibrator: AllowedCallVibrator,
) {
    private var activeNotificationKey: String? = null
    private var activeProvider: ExternalCallProvider? = null
    private var activeIdentityDigest: String? = null

    fun onAllowedIncoming(
        notificationKey: String,
        provider: ExternalCallProvider = ExternalCallProvider.WHATSAPP,
        identityDigest: String? = null,
    ) {
        activeProvider = provider
        activeIdentityDigest = identityDigest
        if (activeNotificationKey == notificationKey) return

        activeNotificationKey?.let(vibrator::stop)
        vibrator.start(notificationKey)
        activeNotificationKey = notificationKey
    }

    fun onNotificationRemoved(notificationKey: String) {
        stopIfActive(notificationKey)
    }

    fun onIncomingTransitionEnded(notificationKey: String) {
        stopIfActive(notificationKey)
    }

    fun onIncomingSessionRejected() {
        activeNotificationKey?.let(vibrator::stop)
        activeNotificationKey = null
    }

    fun stopAll() {
        vibrator.stopAll()
        activeNotificationKey = null
    }

    fun stopProvider(provider: ExternalCallProvider, identityDigest: String? = null) {
        if (activeProvider != provider) return
        if (identityDigest != null && activeIdentityDigest != identityDigest) return
        activeNotificationKey?.let(::stopIfActive)
    }

    private fun stopIfActive(notificationKey: String) {
        if (activeNotificationKey != notificationKey) return

        vibrator.stop(notificationKey)
        activeNotificationKey = null
    }
}
