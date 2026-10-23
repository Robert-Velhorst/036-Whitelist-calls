package com.whitelistcalls

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Blocks each incoming carrier call supplied by Android except a locally
 * whitelisted number. Calls with a withheld/unavailable caller ID are not
 * delivered to CallScreeningService by Android and need a device-level rule.
 */
class IncomingCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return

        val number = callDetails.handle?.schemeSpecificPart
        val allowed = WhitelistStore(this).isAllowed(number)

        val response = CallResponse.Builder()
            .setDisallowCall(!allowed)
            .setRejectCall(!allowed)
            // Keep blocked calls visible to the device owner for safety/auditing.
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)
    }
}
