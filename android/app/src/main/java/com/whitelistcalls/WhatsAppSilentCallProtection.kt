package com.whitelistcalls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings

data class ExternalCallChannelInfo(
    val packageName: String,
    val channelId: String,
    val detectedAtMillis: Long,
    val observedAlertState: ChannelAlertState? = null,
)

enum class SilentCallChannelStatus { UNKNOWN, INCOMPLETE, LOCAL_VIBRATION_UNAVAILABLE, ACTIVE }

data class ChannelAlertState(
    val vibrates: Boolean,
    val hasSound: Boolean,
    val importance: Int,
    val lockscreenVisibility: Int,
)

fun silentCallChannelStatus(
    channel: ChannelAlertState?,
    hasUsableLocalVibration: Boolean = true,
): SilentCallChannelStatus = when {
    channel == null -> SilentCallChannelStatus.UNKNOWN
    !channel.vibrates &&
        !channel.hasSound &&
        channel.importance <= NotificationManager.IMPORTANCE_LOW &&
        channel.lockscreenVisibility in setOf(
            Notification.VISIBILITY_PRIVATE,
            Notification.VISIBILITY_SECRET,
        ) && !hasUsableLocalVibration -> SilentCallChannelStatus.LOCAL_VIBRATION_UNAVAILABLE
    !channel.vibrates &&
        !channel.hasSound &&
        channel.importance <= NotificationManager.IMPORTANCE_LOW &&
        channel.lockscreenVisibility in setOf(
            Notification.VISIBILITY_PRIVATE,
            Notification.VISIBILITY_SECRET,
        ) -> SilentCallChannelStatus.ACTIVE
    else -> SilentCallChannelStatus.INCOMPLETE
}

fun silentProtectionLabel(status: SilentCallChannelStatus): String = when (status) {
    SilentCallChannelStatus.UNKNOWN -> "Silent-call protection setup needed"
    SilentCallChannelStatus.INCOMPLETE -> "Silent-call protection incomplete"
    SilentCallChannelStatus.LOCAL_VIBRATION_UNAVAILABLE -> "Local vibration unavailable"
    SilentCallChannelStatus.ACTIVE -> "Silent-call protection active"
}

fun channelAlertState(channel: NotificationChannel?): ChannelAlertState? = channel?.let {
    ChannelAlertState(
        vibrates = it.shouldVibrate(),
        hasSound = it.sound != null,
        importance = it.importance,
        lockscreenVisibility = it.lockscreenVisibility,
    )
}

internal var channelSettingsIntentConstructor: (String) -> Intent = ::Intent

fun channelSettingsIntent(channel: ExternalCallChannelInfo): Intent = channelSettingsIntentConstructor(
    Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,
)
    .putExtra(Settings.EXTRA_APP_PACKAGE, channel.packageName)
    .putExtra(Settings.EXTRA_CHANNEL_ID, channel.channelId)
