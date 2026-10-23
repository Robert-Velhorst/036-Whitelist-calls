package com.whitelistcalls.externalcalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsAppPackagesTest {
    @Test
    fun routesWeChatPackageWithoutChangingWhatsAppPackages() {
        assertEquals(ExternalCallProvider.WECHAT, providerFor("com.tencent.mm"))
        assertEquals(ExternalCallProvider.WHATSAPP, providerFor("com.whatsapp"))
        assertEquals(ExternalCallProvider.WHATSAPP_BUSINESS, providerFor("com.whatsapp.w4b"))
        assertNull(providerFor("com.tencent.mm.clone"))
    }

    @Test
    fun mapsConsumerWhatsApp() =
        assertEquals(ExternalCallProvider.WHATSAPP, providerFor("com.whatsapp"))

    @Test
    fun mapsBusinessWhatsApp() =
        assertEquals(ExternalCallProvider.WHATSAPP_BUSINESS, providerFor("com.whatsapp.w4b"))

    @Test
    fun ignoresOtherPackages() = assertNull(providerFor("org.telegram.messenger"))
}
