package com.whitelistcalls.externalcalls

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingCallClassifierTest {
    @Test
    fun ignoresAMessageWithoutCallMetadata() =
        assertEquals(
            NotACall,
            classify(
                NotificationSnapshot(
                    "com.whatsapp",
                    null,
                    CallerIdentity.Unresolved,
                    false,
                ),
            ),
        )

    @Test
    fun detectsIncomingCallWithDirectDecline() = assertEquals(
        IncomingCall(ExternalCallProvider.WHATSAPP, CallerIdentity.Phone("+31612345678"), true),
        classify(
            NotificationSnapshot(
                "com.whatsapp",
                CallType.INCOMING,
                CallerIdentity.Phone("+31612345678"),
                true,
            ),
        ),
    )

    @Test
    fun ignoresOngoingCalls() {
        val snapshot = NotificationSnapshot(
            "com.whatsapp",
            CallType.ONGOING,
            CallerIdentity.Unresolved,
            false,
        )

        assertEquals(NotACall, classify(snapshot))
    }
}
