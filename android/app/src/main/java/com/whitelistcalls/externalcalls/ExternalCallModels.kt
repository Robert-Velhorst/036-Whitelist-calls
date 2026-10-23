package com.whitelistcalls.externalcalls

enum class ExternalCallProvider {
    WHATSAPP,
    WHATSAPP_BUSINESS,
    WECHAT,
}

sealed interface CallerIdentity {
    data class Phone(val number: String) : CallerIdentity

    data object Unresolved : CallerIdentity

    data object Ambiguous : CallerIdentity

    data object Group : CallerIdentity
}
