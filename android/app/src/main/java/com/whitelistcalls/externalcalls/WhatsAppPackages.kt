package com.whitelistcalls.externalcalls

fun providerFor(packageName: String): ExternalCallProvider? = when (packageName) {
    "com.whatsapp" -> ExternalCallProvider.WHATSAPP
    "com.whatsapp.w4b" -> ExternalCallProvider.WHATSAPP_BUSINESS
    "com.tencent.mm" -> ExternalCallProvider.WECHAT
    else -> null
}
