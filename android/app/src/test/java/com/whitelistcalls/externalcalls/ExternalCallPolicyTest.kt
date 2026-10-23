package com.whitelistcalls.externalcalls

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalCallPolicyTest {
    @Test
    fun rejectsUnknownCallerWhenFilteringEnabled() = assertEquals(
        CallDecision.REJECT,
        decide(true, CallerIdentity.Unresolved) { false },
    )

    @Test
    fun allowsWhitelistedPhone() = assertEquals(
        CallDecision.ALLOW,
        decide(true, CallerIdentity.Phone("+31612345678")) { it == "+31612345678" },
    )

    @Test
    fun rejectsGroupCallsWhenFilteringEnabled() =
        assertEquals(CallDecision.REJECT, decide(true, CallerIdentity.Group) { true })

    @Test
    fun rejectsAmbiguousCallerWhenFilteringEnabled() =
        assertEquals(CallDecision.REJECT, decide(true, CallerIdentity.Ambiguous) { true })
}
