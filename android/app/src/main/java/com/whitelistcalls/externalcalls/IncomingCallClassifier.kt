package com.whitelistcalls.externalcalls

enum class CallType {
    INCOMING,
    ONGOING,
}

data class NotificationSnapshot(
    val packageName: String,
    val callType: CallType?,
    val callerIdentity: CallerIdentity,
    val hasDirectDeclineIntent: Boolean,
    val personUriKind: PersonUriKind? = null,
    val contactCandidateCount: Int? = null,
    val relatedPeopleCount: Int = 0,
    val relatedPeoplePhoneCount: Int = 0,
)

sealed interface IncomingCallClassification

data object NotACall : IncomingCallClassification

data class IncomingCall(
    val provider: ExternalCallProvider,
    val identity: CallerIdentity,
    val hasDirectDeclineIntent: Boolean,
) : IncomingCallClassification

fun classify(snapshot: NotificationSnapshot): IncomingCallClassification {
    val provider = providerFor(snapshot.packageName) ?: return NotACall
    if (snapshot.callType != CallType.INCOMING) return NotACall

    return IncomingCall(provider, snapshot.callerIdentity, snapshot.hasDirectDeclineIntent)
}
