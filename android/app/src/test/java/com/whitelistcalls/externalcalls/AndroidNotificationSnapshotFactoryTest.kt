package com.whitelistcalls.externalcalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidNotificationSnapshotFactoryTest {
    @Test
    fun turnsAnApi34IncomingCallIntoIncomingSnapshot() = assertEquals(
        CallType.INCOMING,
        AndroidNotificationSnapshotFactory(apiLevel = 34).from(
            callType = 1,
            personMetadata = StandardPersonMetadata(uri = null, displayName = "Alice"),
            declinePresent = true,
        ).callType,
    )

    @Test
    fun neverMarksAnOngoingCallAsIncoming() = assertNotEquals(
        CallType.INCOMING,
        AndroidNotificationSnapshotFactory(apiLevel = 34).from(
            callType = 2,
            personMetadata = StandardPersonMetadata(uri = null, displayName = "Alice"),
            declinePresent = true,
        ).callType,
    )

    @Test
    fun api34RejectsMissingCallTypeFromDirectPath() = assertNull(
        AndroidNotificationSnapshotFactory(apiLevel = 34).from(
            callType = null,
            personMetadata = StandardPersonMetadata(uri = null, displayName = "Alice"),
            declinePresent = true,
        ).callType,
    )

    @Test
    fun api33RequiresBothStandardPersonAndExplicitDeclineIntent() {
        val factory = AndroidNotificationSnapshotFactory(apiLevel = 33)

        assertNull(
            factory.from(callType = null, personMetadata = null, declinePresent = true).callType,
        )
        assertNull(
            factory.from(
                callType = null,
                personMetadata = StandardPersonMetadata(uri = null, displayName = "Alice"),
                declinePresent = false,
            ).callType,
        )
        assertEquals(
            CallType.INCOMING,
            factory.from(
                callType = null,
                personMetadata = StandardPersonMetadata(uri = null, displayName = "Alice"),
                declinePresent = true,
            ).callType,
        )
    }

    @Test
    fun resolvesCallerFromTelPersonUri() {
        val factory = AndroidNotificationSnapshotFactory(
            apiLevel = 34,
            identityResolver = CallerIdentityResolver { emptyList() },
        )

        assertEquals(
            CallerIdentity.Phone("+31612345678"),
            factory.from(
                callType = 1,
                personMetadata = StandardPersonMetadata(
                    uri = "tel:+31612345678",
                    displayName = "Alice",
                ),
                declinePresent = true,
            ).callerIdentity,
        )
    }

    @Test
    fun relatedPeopleListSuppliesTheUniquePhoneWhenCallPersonIsAmbiguous() {
        val factory = AndroidNotificationSnapshotFactory(
            apiLevel = 34,
            identityResolver = CallerIdentityResolver {
                listOf("+31612345678", "+31687654321")
            },
        )

        val snapshot = factory.from(
            callType = 1,
            personMetadata = StandardPersonMetadata(uri = null, displayName = "Alice"),
            relatedPeopleMetadata = listOf(
                StandardPersonMetadata(uri = "tel:+31612345678", displayName = "Alice"),
            ),
            declinePresent = true,
        )

        assertEquals(CallerIdentity.Phone("+31612345678"), snapshot.callerIdentity)
        assertEquals(1, snapshot.relatedPeopleCount)
        assertEquals(1, snapshot.relatedPeoplePhoneCount)
    }

    @Test
    fun conflictingRelatedPeoplePhonesRemainAmbiguous() {
        val snapshot = AndroidNotificationSnapshotFactory(apiLevel = 34).from(
            callType = 1,
            personMetadata = StandardPersonMetadata(uri = null, displayName = "Alice"),
            relatedPeopleMetadata = listOf(
                StandardPersonMetadata(uri = "tel:+31612345678", displayName = "Alice"),
                StandardPersonMetadata(uri = "tel:+31687654321", displayName = "Alice"),
            ),
            declinePresent = true,
        )

        assertEquals(CallerIdentity.Ambiguous, snapshot.callerIdentity)
        assertEquals(2, snapshot.relatedPeopleCount)
        assertEquals(2, snapshot.relatedPeoplePhoneCount)
    }

    @Test
    fun standardGroupSignalOverridesIndividualPersonMetadata() {
        val factory = AndroidNotificationSnapshotFactory(apiLevel = 34)

        assertEquals(
            CallerIdentity.Group,
            factory.from(
                callType = 1,
                personMetadata = StandardPersonMetadata(
                    uri = "tel:+31612345678",
                    displayName = "Family",
                ),
                isGroupConversation = true,
                declinePresent = true,
            ).callerIdentity,
        )
    }

    @Test
    fun missingGroupSignalDoesNotGuessFromDisplayName() {
        val factory = AndroidNotificationSnapshotFactory(apiLevel = 34)

        assertEquals(
            CallerIdentity.Unresolved,
            factory.from(
                callType = 1,
                personMetadata = StandardPersonMetadata(uri = null, displayName = "Family"),
                isGroupConversation = null,
                declinePresent = true,
            ).callerIdentity,
        )
    }

    @Test
    fun api34RejectsScreeningCallTypeFromDirectPath() = assertNull(
        AndroidNotificationSnapshotFactory(apiLevel = 34).from(
            callType = 3,
            personMetadata = StandardPersonMetadata(uri = "tel:+31612345678", displayName = "Alice"),
            declinePresent = true,
        ).callType,
    )
}
