package com.whitelistcalls

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WhatsAppSilentCallProtectionTest {
    @Test
    fun nullChannelStateIsUnknown() = assertEquals(
        SilentCallChannelStatus.UNKNOWN,
        silentCallChannelStatus(null),
    )

    @Test
    fun fullySilentChannelIsActive() = assertEquals(
        SilentCallChannelStatus.ACTIVE,
        silentCallChannelStatus(
            ChannelAlertState(
                vibrates = false,
                hasSound = false,
                importance = NotificationManager.IMPORTANCE_LOW,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
            ),
        ),
    )

    @Test
    fun fullySilentChannelWithoutUsableLocalVibrationIsUnavailable() = assertEquals(
        SilentCallChannelStatus.LOCAL_VIBRATION_UNAVAILABLE,
        silentCallChannelStatus(
            channel = ChannelAlertState(
                vibrates = false,
                hasSound = false,
                importance = NotificationManager.IMPORTANCE_LOW,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
            ),
            hasUsableLocalVibration = false,
        ),
    )

    @Test
    fun audibleChannelIsIncomplete() = assertEquals(
        SilentCallChannelStatus.INCOMPLETE,
        silentCallChannelStatus(
            ChannelAlertState(
                vibrates = false,
                hasSound = true,
                importance = NotificationManager.IMPORTANCE_LOW,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
            ),
        ),
    )

    @Test
    fun channelInfoRoundTripsAndCanBeCleared() {
        val store = newStore()
        val channel = ExternalCallChannelInfo("com.whatsapp", "runtime-channel", 123L)

        store.recordCallChannel(channel)

        assertEquals(channel, store.callChannelInfo("com.whatsapp"))
        store.clearCallChannelInfo("com.whatsapp")
        assertNull(store.callChannelInfo("com.whatsapp"))
    }

    @Test
    fun channelInfoRoundTripsLastObservedAlertState() {
        val store = newStore()
        val observedAlertState = ChannelAlertState(
            vibrates = false,
            hasSound = true,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
        )

        store.recordCallChannel(
            ExternalCallChannelInfo(
                packageName = "com.whatsapp",
                channelId = "runtime-channel",
                detectedAtMillis = 123L,
                observedAlertState = observedAlertState,
            ),
        )

        assertEquals(observedAlertState, store.callChannelInfo("com.whatsapp")?.observedAlertState)
    }

    @Test
    fun channelInfoWithoutRankingObservationReturnsNullAlertState() {
        val store = newStore()

        store.recordCallChannel(
            ExternalCallChannelInfo("com.whatsapp", "runtime-channel", 123L),
        )

        assertNull(store.callChannelInfo("com.whatsapp")?.observedAlertState)
    }

    @Test
    fun observedChannelsAreNewestFirst() {
        val store = newStore()
        store.recordCallChannel(ExternalCallChannelInfo("com.whatsapp", "personal", 1L))
        store.recordCallChannel(ExternalCallChannelInfo("com.whatsapp.w4b", "business", 2L))

        assertEquals(
            listOf("business", "personal"),
            store.observedCallChannels().map(ExternalCallChannelInfo::channelId),
        )
    }

    @Test
    fun observedChannelsExcludeArbitraryPersistedPackages() {
        val store = newStore()
        store.recordCallChannel(ExternalCallChannelInfo("com.example.other", "other", 3L))

        assertEquals(emptyList<ExternalCallChannelInfo>(), store.observedCallChannels())
    }

    @Test
    fun settingsIntentTargetsDetectedChannel() {
        val originalConstructor = channelSettingsIntentConstructor
        var requestedAction: String? = null
        lateinit var createdIntent: RecordingIntent
        channelSettingsIntentConstructor = { action ->
            requestedAction = action
            RecordingIntent(action).also { createdIntent = it }
        }

        try {
            val result = channelSettingsIntent(
                ExternalCallChannelInfo("com.whatsapp", "calls-v2", 1L),
            )

            assertSame(createdIntent, result)
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, requestedAction)
            assertEquals("com.whatsapp", createdIntent.extras[Settings.EXTRA_APP_PACKAGE])
            assertEquals("calls-v2", createdIntent.extras[Settings.EXTRA_CHANNEL_ID])
        } finally {
            channelSettingsIntentConstructor = originalConstructor
        }
    }

    @Test
    fun incompleteLabelIsExact() = assertEquals(
        "Silent-call protection incomplete",
        silentProtectionLabel(SilentCallChannelStatus.INCOMPLETE),
    )

    @Test
    fun activeLabelIsExact() = assertEquals(
        "Silent-call protection active",
        silentProtectionLabel(SilentCallChannelStatus.ACTIVE),
    )

    private fun newStore(): WhatsAppSettingsStore = WhatsAppSettingsStore(
        preferences = InMemorySharedPreferences(),
        defaultRegion = "NL",
    )

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defaultValue: String?): String? =
            values[key] as? String ?: defaultValue

        override fun getStringSet(key: String, defaultValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet()
                ?: defaultValues?.toMutableSet()

        override fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue

        override fun getLong(key: String, defaultValue: Long): Long = values[key] as? Long ?: defaultValue

        override fun getFloat(key: String, defaultValue: Float): Float = values[key] as? Float ?: defaultValue

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            values[key] as? Boolean ?: defaultValue

        override fun contains(key: String): Boolean = key in values

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
    }

    private class Editor(
        private val values: MutableMap<String, Any>,
    ) : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            if (value == null) removals += key else updates[key] = value
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (values == null) removals += key else updates[key] = values.toSet()
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { updates[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { updates[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { updates[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { updates[key] = value }

        override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

        override fun clear(): SharedPreferences.Editor = apply { values.clear() }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            removals.forEach(values::remove)
            values.putAll(updates)
        }
    }

    private class RecordingIntent(
        action: String,
    ) : Intent(action) {
        val extras = mutableMapOf<String, String>()

        override fun putExtra(name: String, value: String?): Intent = apply {
            if (value != null) extras[name] = value
        }
    }
}
