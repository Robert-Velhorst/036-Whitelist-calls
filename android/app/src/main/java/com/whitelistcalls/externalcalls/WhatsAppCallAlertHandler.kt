package com.whitelistcalls.externalcalls

class WhatsAppCallAlertHandler(
    private val allowedCallAlertCoordinator: AllowedCallAlertCoordinator,
) {
    fun onHandlingResult(
        notificationKey: String,
        result: NotificationHandlingResult,
    ) {
        when (result) {
            NotificationHandlingResult.ALLOWED ->
                allowedCallAlertCoordinator.onAllowedIncoming(notificationKey)
            NotificationHandlingResult.IGNORED ->
                allowedCallAlertCoordinator.onIncomingTransitionEnded(notificationKey)
            NotificationHandlingResult.NO_DIRECT_DECLINE,
            NotificationHandlingResult.DUPLICATE,
            NotificationHandlingResult.DECLINED,
            NotificationHandlingResult.DECLINE_FAILED ->
                allowedCallAlertCoordinator.onIncomingSessionRejected()
        }
    }
}
