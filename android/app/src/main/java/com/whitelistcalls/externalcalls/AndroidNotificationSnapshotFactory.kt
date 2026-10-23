package com.whitelistcalls.externalcalls

private const val API_LEVEL_ANDROID_14 = 34
private const val PLATFORM_CALL_TYPE_INCOMING = 1
private const val PLATFORM_CALL_TYPE_ONGOING = 2

/**
 * Pure conversion of Android notification fields into the app's call model.
 * Framework types stay in the notification-listener boundary so these safety
 * rules remain executable in local unit tests.
 */
class AndroidNotificationSnapshotFactory(
    private val apiLevel: Int,
    private val identityResolver: CallerIdentityResolver = CallerIdentityResolver(),
) {
    fun from(
        packageName: String = "com.whatsapp",
        callType: Int?,
        personMetadata: StandardPersonMetadata?,
        relatedPeopleMetadata: List<StandardPersonMetadata> = emptyList(),
        isGroupConversation: Boolean? = null,
        declinePresent: Boolean,
    ): NotificationSnapshot {
        val mappedCallType = mapCallType(callType, personMetadata != null, declinePresent)
        val primaryResolution = if (mappedCallType == CallType.INCOMING && isGroupConversation != true) {
            identityResolver.resolveDetails(personMetadata)
        } else {
            null
        }
        val relatedResolutions = if (mappedCallType == CallType.INCOMING && isGroupConversation != true) {
            relatedPeopleMetadata.map(identityResolver::resolveDetails)
        } else {
            emptyList()
        }
        val relatedPhoneResolutions = relatedResolutions
            .filter { it.identity is CallerIdentity.Phone }
            .distinctBy { (it.identity as CallerIdentity.Phone).number }
        val resolution = when {
            primaryResolution?.identity is CallerIdentity.Phone -> primaryResolution
            relatedPhoneResolutions.size == 1 -> relatedPhoneResolutions.single()
            relatedPhoneResolutions.size > 1 -> primaryResolution?.copy(identity = CallerIdentity.Ambiguous)
            else -> primaryResolution
        }
        val identity = when {
            mappedCallType != CallType.INCOMING -> CallerIdentity.Unresolved
            isGroupConversation == true -> CallerIdentity.Group
            else -> requireNotNull(resolution).identity
        }

        return NotificationSnapshot(
            packageName = packageName,
            callType = mappedCallType,
            callerIdentity = identity,
            hasDirectDeclineIntent = declinePresent,
            personUriKind = resolution?.personUriKind,
            contactCandidateCount = resolution?.contactCandidateCount,
            relatedPeopleCount = relatedPeopleMetadata.size,
            relatedPeoplePhoneCount = relatedPhoneResolutions.size,
        )
    }

    private fun mapCallType(
        callType: Int?,
        hasPersonMetadata: Boolean,
        declinePresent: Boolean,
    ): CallType? {
        if (callType == PLATFORM_CALL_TYPE_ONGOING) return CallType.ONGOING

        return if (apiLevel >= API_LEVEL_ANDROID_14) {
            CallType.INCOMING.takeIf { callType == PLATFORM_CALL_TYPE_INCOMING }
        } else {
            CallType.INCOMING.takeIf { hasPersonMetadata && declinePresent }
        }
    }
}
