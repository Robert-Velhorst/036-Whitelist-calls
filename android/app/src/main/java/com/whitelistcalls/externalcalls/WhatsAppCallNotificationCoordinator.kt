package com.whitelistcalls.externalcalls

enum class DirectDeclineSendResult {
    SENT,
    CANCELED,
}

enum class NotificationHandlingResult {
    IGNORED,
    ALLOWED,
    NO_DIRECT_DECLINE,
    DUPLICATE,
    DECLINED,
    DECLINE_FAILED,
}

/**
 * Pure orchestration boundary for notification-call handling. Android owns
 * notification parsing and PendingIntent delivery; all safety ordering lives
 * here so it can be verified on the local JVM.
 */
class WhatsAppCallNotificationCoordinator(
    private val sessions: BlockedCallSessionStore,
    private val diagnostics: BoundedDiagnostics,
) {
    fun onNotificationPosted(
        snapshot: NotificationSnapshot,
        notificationKey: String,
        hasCallerMetadata: Boolean,
        filteringEnabled: () -> Boolean,
        isAllowed: (String) -> Boolean,
        nowMillis: Long,
        sendDirectDecline: (() -> DirectDeclineSendResult)?,
        diagnosticsMetadata: DiagnosticsMetadata? = null,
    ): NotificationHandlingResult {
        val call = classify(snapshot) as? IncomingCall ?: return NotificationHandlingResult.IGNORED

        val decision = decide(
            filteringEnabled = filteringEnabled(),
            identity = call.identity,
            isAllowed = isAllowed,
        )
        if (decision == CallDecision.ALLOW) return NotificationHandlingResult.ALLOWED

        if (!call.hasDirectDeclineIntent || sendDirectDecline == null) {
            return NotificationHandlingResult.NO_DIRECT_DECLINE
        }
        if (!sessions.shouldAttemptReject(call.provider, notificationKey, nowMillis)) {
            return NotificationHandlingResult.DUPLICATE
        }

        return when (sendDirectDecline()) {
            DirectDeclineSendResult.SENT -> NotificationHandlingResult.DECLINED
            DirectDeclineSendResult.CANCELED -> {
                diagnostics.record(
                    eventCode = "WA_DIRECT_REJECT_FAILED",
                    notificationKey = notificationKey,
                    metadata = diagnosticsMetadata ?: DiagnosticsMetadata(
                        packageName = snapshot.packageName,
                        category = null,
                        extrasKeyNames = null,
                        callTypePresent = false,
                        callTypeValue = null,
                        hasCallerMetadata = hasCallerMetadata,
                        hasDeclineMetadata = true,
                        actionCount = null,
                        sdkInt = null,
                        deviceManufacturer = null,
                        deviceModel = null,
                        whatsAppPackageVersion = null,
                    ),
                    timestampMillis = nowMillis,
                    callerValue = (call.identity as? CallerIdentity.Phone)?.number,
                )
                NotificationHandlingResult.DECLINE_FAILED
            }
        }
    }

    fun onNotificationRemoved(packageName: String, notificationKey: String) {
        val provider = providerFor(packageName) ?: return
        sessions.remove(provider, notificationKey)
    }
}
