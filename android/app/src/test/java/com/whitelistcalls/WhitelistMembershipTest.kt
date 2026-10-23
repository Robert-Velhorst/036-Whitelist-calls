package com.whitelistcalls

import com.whitelistcalls.externalcalls.CallDecision
import com.whitelistcalls.externalcalls.CallerIdentity
import com.whitelistcalls.externalcalls.decide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistMembershipTest {
    private val whitelist = setOf("+31612345678")
    private val normalize: (String?) -> String? = { raw -> raw?.filter { it.isDigit() || it == '+' } }

    @Test
    fun whatsappFilteringRejectsNonMemberWhenCellularProtectionIsDisabled() {
        val membership = WhitelistMembership(whitelist, normalize)

        assertTrue(isCellularCallAllowed(protectionEnabled = false) {
            membership.contains("+31600000000")
        })
        assertEquals(
            CallDecision.REJECT,
            decide(
                filteringEnabled = true,
                identity = CallerIdentity.Phone("+31600000000"),
                isAllowed = membership::contains,
            ),
        )
    }

    @Test
    fun whatsappFilteringAllowsMemberWhenCellularProtectionIsDisabled() {
        val membership = WhitelistMembership(whitelist, normalize)

        assertTrue(isCellularCallAllowed(protectionEnabled = false) {
            membership.contains("+31612345678")
        })
        assertEquals(
            CallDecision.ALLOW,
            decide(
                filteringEnabled = true,
                identity = CallerIdentity.Phone("+31612345678"),
                isAllowed = membership::contains,
            ),
        )
    }
}
