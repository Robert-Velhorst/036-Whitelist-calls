package com.whitelistcalls.externalcalls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedCallSessionStoreTest {
    private val store = BlockedCallSessionStore()

    @Test
    fun permitsOnlyOneRejectForRepeatedNotificationUpdates() {
        assertTrue(store.shouldAttemptReject(ExternalCallProvider.WHATSAPP, "key", 0))
        assertFalse(store.shouldAttemptReject(ExternalCallProvider.WHATSAPP, "key", 1))
    }

    @Test
    fun permitsANewRejectAfterRemoval() {
        store.shouldAttemptReject(ExternalCallProvider.WHATSAPP, "key", 0)

        store.remove(ExternalCallProvider.WHATSAPP, "key")

        assertTrue(store.shouldAttemptReject(ExternalCallProvider.WHATSAPP, "key", 2))
    }

    @Test
    fun permitsANewRejectAfterTheSixtySecondSessionExpiry() {
        store.shouldAttemptReject(ExternalCallProvider.WHATSAPP, "key", 0)

        assertTrue(store.shouldAttemptReject(ExternalCallProvider.WHATSAPP, "key", 60_000))
    }

    @Test
    fun keepsProvidersSeparateForTheSameNotificationKey() {
        assertTrue(store.shouldAttemptReject(ExternalCallProvider.WHATSAPP, "key", 0))

        assertTrue(store.shouldAttemptReject(ExternalCallProvider.WHATSAPP_BUSINESS, "key", 1))
    }

    @Test
    fun removesOnlyTheRecordedProviderAndNotificationKeyPair() {
        store.shouldAttemptReject(ExternalCallProvider.WHATSAPP, "key", 0)
        store.shouldAttemptReject(ExternalCallProvider.WHATSAPP_BUSINESS, "key", 0)

        store.remove(ExternalCallProvider.WHATSAPP, "key")

        assertTrue(store.shouldAttemptReject(ExternalCallProvider.WHATSAPP, "key", 1))
        assertFalse(store.shouldAttemptReject(ExternalCallProvider.WHATSAPP_BUSINESS, "key", 1))
    }
}
