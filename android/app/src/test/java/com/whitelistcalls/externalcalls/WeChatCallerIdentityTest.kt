package com.whitelistcalls.externalcalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatCallerIdentityTest {
    @Test fun personUriWinsOverOtherOpaqueFields() {
        val candidate = WeChatCallerIdentity.extract(
            WeChatIdentityObservation(
                personUri = "wechat://contact/opaque-a",
                personKey = "different-key",
                shortcutId = "shortcut-a",
                displayName = "Alice",
            ),
        )

        assertTrue(candidate is WeChatIdentityCandidate.Supported)
        assertEquals(
            WeChatCallerIdentity.digest("person-uri:wechat://contact/opaque-a"),
            (candidate as WeChatIdentityCandidate.Supported).digest,
        )
    }

    @Test fun nameOnlyNotificationIsNeverSupported() {
        assertEquals(
            WeChatIdentityCandidate.NameOnly,
            WeChatCallerIdentity.extract(WeChatIdentityObservation(displayName = "Alice")),
        )
    }

    @Test fun digestDoesNotContainRawIdentityValue() {
        val digest = WeChatCallerIdentity.digest("shortcut:secret-contact-id")
        assertFalse(digest.contains("secret-contact-id"))
    }
}
