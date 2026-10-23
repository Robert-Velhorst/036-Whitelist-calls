package com.whitelistcalls.externalcalls

enum class CallDecision {
    ALLOW,
    REJECT,
}

fun decide(
    filteringEnabled: Boolean,
    identity: CallerIdentity,
    isAllowed: (String) -> Boolean,
): CallDecision =
    if (!filteringEnabled) CallDecision.ALLOW
    else if (identity is CallerIdentity.Phone && isAllowed(identity.number)) CallDecision.ALLOW
    else CallDecision.REJECT
