package com.whitelistcalls.externalcalls

enum class WeChatHandlingOutcome { DIAGNOSTIC_ONLY, ALLOWED, BLOCKED }

class WeChatCallNotificationHandler(
    private val isAllowedDigest: (String) -> Boolean,
    private val allowedCallAlertCoordinator: AllowedCallAlertCoordinator,
) {
    fun onIncoming(
        notificationKey: String,
        identity: WeChatIdentityCandidate,
        filteringEnabled: Boolean,
    ): WeChatHandlingOutcome {
        if (!filteringEnabled) {
            allowedCallAlertCoordinator.onNotificationRemoved(notificationKey)
            return WeChatHandlingOutcome.DIAGNOSTIC_ONLY
        }
        if (identity is WeChatIdentityCandidate.Supported && isAllowedDigest(identity.digest)) {
            allowedCallAlertCoordinator.onAllowedIncoming(notificationKey, ExternalCallProvider.WECHAT, identity.digest)
            return WeChatHandlingOutcome.ALLOWED
        }
        allowedCallAlertCoordinator.onNotificationRemoved(notificationKey)
        return WeChatHandlingOutcome.BLOCKED
    }

    fun onNotificationRemoved(notificationKey: String) {
        allowedCallAlertCoordinator.onNotificationRemoved(notificationKey)
    }
}

internal fun isIncomingWeChatCall(
    apiLevel: Int,
    callType: Int?,
    hasPersonMetadata: Boolean,
    hasDeclineIntent: Boolean,
): Boolean = when {
    apiLevel < 31 -> false
    apiLevel >= 34 -> callType == 1
    callType != null && callType != 1 -> false
    else -> hasPersonMetadata && hasDeclineIntent
}

/** Phone metadata is never promoted into an opaque WeChat identity. */
internal fun weChatNotificationIdentity(
    observation: WeChatIdentityObservation,
): WeChatIdentityCandidate = WeChatCallerIdentity.extract(
    observation.copy(
        personUri = observation.personUri.opaqueOrNull(),
        personKey = observation.personKey.opaqueOrNull(),
        shortcutId = observation.shortcutId.opaqueOrNull(),
    ),
)

private fun String?.opaqueOrNull(): String? {
    val value = this?.takeIf(String::isNotBlank) ?: return null
    val trimmed = value.trim()
    if (trimmed.startsWith("tel:", ignoreCase = true) ||
        trimmed.startsWith("sip:", ignoreCase = true) ||
        trimmed.matches(Regex("[+0-9().\\s-]+"))
    ) return null
    return value
}
