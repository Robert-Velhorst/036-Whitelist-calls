package com.whitelistcalls

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppSetupStatusTest {
    @Test
    fun reportsUnconfiguredWhenFilteringIsDisabled() = assertEquals(
        WhatsAppSetupStatus.UNCONFIGURED,
        WhatsAppSetupStatus.from(
            filteringEnabled = false,
            notificationAccessGranted = true,
            directDeclineUnavailable = false,
        ),
    )

    @Test
    fun reportsPermissionRequiredWhenFilteringIsEnabledWithoutAccess() = assertEquals(
        WhatsAppSetupStatus.PERMISSION_REQUIRED,
        WhatsAppSetupStatus.from(
            filteringEnabled = true,
            notificationAccessGranted = false,
            directDeclineUnavailable = false,
        ),
    )

    @Test
    fun reportsReadyWhenFilteringAndNotificationAccessAreEnabled() = assertEquals(
        WhatsAppSetupStatus.READY,
        WhatsAppSetupStatus.from(
            filteringEnabled = true,
            notificationAccessGranted = true,
            directDeclineUnavailable = false,
        ),
    )

    @Test
    fun reportsDirectDeclineUnavailableAfterUnsupportedIncomingCall() = assertEquals(
        WhatsAppSetupStatus.DIRECT_DECLINE_UNAVAILABLE,
        WhatsAppSetupStatus.from(
            filteringEnabled = true,
            notificationAccessGranted = true,
            directDeclineUnavailable = true,
        ),
    )
}
