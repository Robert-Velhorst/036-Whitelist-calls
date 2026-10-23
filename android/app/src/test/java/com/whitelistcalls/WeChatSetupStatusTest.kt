package com.whitelistcalls

import android.content.SharedPreferences
import com.whitelistcalls.externalcalls.WeChatIdentityCandidate
import com.whitelistcalls.externalcalls.AllowedCallAlertCoordinator
import com.whitelistcalls.externalcalls.WeChatCallNotificationHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatSetupStatusTest {
    @Test
    fun latestCandidateReportsOnlyRedactedFingerprintCountAndEligibility() {
        val store = WeChatSettingsStore(InMemorySharedPreferences(), 1)
        assertNull(store.recentCandidate())
        store.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 1L)
        assertEquals("aaaaaaaa…", store.recentCandidate()!!.fingerprint)
        assertEquals(1, store.recentCandidate()!!.observationCount)
        assertFalse(store.recentCandidate()!!.canBind)
        store.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 2L)
        assertEquals(2, store.recentCandidate()!!.observationCount)
        assertTrue(store.recentCandidate()!!.canBind)
        store.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_B), 3L)
        assertEquals("bbbbbbbb…", store.recentCandidate()!!.fingerprint)
        assertEquals(1, store.recentCandidate()!!.observationCount)
        assertFalse(store.recentCandidate()!!.canBind)
        store.recordCandidate(WeChatIdentityCandidate.NameOnly, 4L)
        assertNull(store.recentCandidate())
    }

    @Test
    fun bindingRejectsChangedCandidateInsteadOfSilentlyBindingUndisplayedCaller() {
        val store = WeChatSettingsStore(InMemorySharedPreferences(), 1)
        store.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 1L)
        store.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 2L)
        val displayed = store.recentCandidate()!!
        store.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_B), 3L)
        store.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_B), 4L)
        assertFalse(store.bindRecentCaller("Caller A", 5L, expectedDigest = displayed.digest))
        assertTrue(store.verifiedBindings().isEmpty())
        assertTrue(store.bindRecentCaller("Caller B", 6L, expectedDigest = DIGEST_B))
        assertFalse(store.recentCandidate()!!.canBind)
    }

    @Test
    fun weChatSwitchPreservesSavedChoiceButRequiresMasterAndBinding() {
        for (saved in listOf(false, true)) {
            for (master in listOf(false, true)) {
                for (binding in listOf(false, true)) {
                    val state = weChatFilteringToggleState(master, saved, binding)
                    assertEquals(saved, state.checked)
                    assertEquals(master && binding, state.enabled)
                }
            }
        }
    }

    @Test
    fun newerUnstableCallerCannotBindOlderStableCaller() {
        val store = WeChatSettingsStore(InMemorySharedPreferences(), 1)
        store.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 1L)
        store.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 2L)
        store.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_B), 3L)

        assertFalse(store.bindRecentCaller("Recent caller", 4L))
        assertTrue(store.verifiedBindings().isEmpty())
    }

    @Test
    fun newerUnsupportedObservationCannotBindOlderStableCallerAcrossStores() {
        for (latest in listOf(WeChatIdentityCandidate.NameOnly, WeChatIdentityCandidate.Missing,
            WeChatIdentityCandidate.Supported("raw identity"))) {
            val preferences = InMemorySharedPreferences()
            val listener = WeChatSettingsStore(preferences, 1)
            listener.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 1L)
            listener.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 2L)
            listener.recordCandidate(latest, 3L)

            assertFalse(WeChatSettingsStore(preferences, 1).bindRecentCaller("Recent caller", 4L))
            assertTrue(preferences.all.isEmpty())
        }
    }

    @Test
    fun unsupportedNotificationInvalidatesPreviouslyBindableCandidate() {
        val store = WeChatSettingsStore(InMemorySharedPreferences(), 1)
        store.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 1L)
        store.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 2L)
        store.recordUnsupportedNotification()
        assertFalse(store.bindRecentCaller("Old caller", 4L))
    }

    @Test
    fun disablingWeChatImmediatelyStopsItsAlert() = withActiveWeChat { store, events ->
        store.setFilteringEnabled(false)
        assertEquals(listOf("start:wechat", "stop:wechat"), events)
    }

    @Test
    fun deletingActiveBindingImmediatelyStopsItsAlertEvenWithOtherBindings() = withActiveWeChat { store, events ->
        store.removeBinding(DIGEST_A)
        assertTrue(store.isFilteringEnabled())
        assertEquals(listOf("start:wechat", "stop:wechat"), events)
    }

    @Test
    fun deletingAnotherBindingDoesNotStopActiveCaller() = withActiveWeChat { store, events ->
        store.removeBinding(DIGEST_B)
        assertEquals(listOf("start:wechat"), events)
    }

    @Test
    fun disablingWhatsAppLeavesWeChatAlertRunning() = withActiveWeChat { _, events ->
        WhatsAppSettingsStore(InMemorySharedPreferences()).setFilteringEnabled(false)
        assertEquals(listOf("start:wechat"), events)
    }

    @Test
    fun disablingWhatsAppImmediatelyStopsWhatsAppAlert() {
        val events = mutableListOf<String>()
        val coordinator = AllowedCallAlertCoordinator(recordingVibrator(events))
        AllowedCallVibrationController.register(coordinator)
        try {
            coordinator.onAllowedIncoming("whatsapp")
            WhatsAppSettingsStore(InMemorySharedPreferences()).setFilteringEnabled(false)
            assertEquals(listOf("start:whatsapp", "stop:whatsapp"), events)
        } finally {
            AllowedCallVibrationController.unregister(coordinator)
        }
    }

    @Test
    fun masterDisableStillStopsWeChatAlert() = withActiveWeChat { _, events ->
        AllowedCallVibrationController.cancelActiveAlert()
        assertEquals(listOf("start:wechat", "stopAll"), events)
    }

    private fun withActiveWeChat(check: (WeChatSettingsStore, List<String>) -> Unit) {
        val store = WeChatSettingsStore(InMemorySharedPreferences(), 1)
        for (digest in listOf(DIGEST_A, DIGEST_B)) {
            store.recordCandidate(WeChatIdentityCandidate.Supported(digest), 1L)
            store.recordCandidate(WeChatIdentityCandidate.Supported(digest), 2L)
            assertTrue(store.bindRecentCaller("Caller", 3L))
        }
        store.setFilteringEnabled(true)
        val events = mutableListOf<String>()
        val coordinator = AllowedCallAlertCoordinator(recordingVibrator(events))
        AllowedCallVibrationController.register(coordinator)
        try {
            WeChatCallNotificationHandler(store::isAllowedDigest, coordinator)
                .onIncoming("wechat", WeChatIdentityCandidate.Supported(DIGEST_A), true)
            check(store, events)
        } finally {
            AllowedCallVibrationController.unregister(coordinator)
        }
    }

    private fun recordingVibrator(events: MutableList<String>) = object : AllowedCallVibrator {
        override fun start(notificationKey: String) { events += "start:$notificationKey" }
        override fun stop(notificationKey: String) { events += "stop:$notificationKey" }
        override fun stopAll() { events += "stopAll" }
    }

    @Test
    fun setupStaysRedUntilAccessFilteringAndBindingAllExist() {
        for (access in listOf(false, true)) {
            for (filtering in listOf(false, true)) {
                for (binding in listOf(false, true)) {
                    if (access && filtering && binding) continue
                    assertFalse(WeChatSetupStatus.from(access, filtering, binding).isReady)
                }
            }
        }
    }

    @Test
    fun setupIsGreenWithAccessFilteringAndVerifiedBinding() {
        assertTrue(WeChatSetupStatus.from(true, true, true).isReady)
    }

    @Test
    fun enrollmentObservationsStayBoundedAndNeverPersist() {
        val preferences = InMemorySharedPreferences()
        val listener = WeChatSettingsStore(preferences, schemaVersion = 1)
        listener.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 1L)
        listener.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 2L)
        repeat(64) { index ->
            listener.recordCandidate(
                WeChatIdentityCandidate.Supported(index.toString(16).padStart(64, '0')),
                3L + index,
            )
        }
        val activity = WeChatSettingsStore(preferences, schemaVersion = 1)

        assertFalse(activity.bindRecentCaller("Expired caller", 100L))
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun listenerObservationsAllowAnotherStoreToBindRecentCaller() {
        val preferences = InMemorySharedPreferences()
        val listenerStore = WeChatSettingsStore(preferences, schemaVersion = 1)
        val activityStore = WeChatSettingsStore(preferences, schemaVersion = 1)
        assertFalse(activityStore.isFilteringEligible())

        listenerStore.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 1L)
        assertFalse(activityStore.bindRecentCaller("Alice", 2L))
        listenerStore.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 3L)

        assertTrue(activityStore.bindRecentCaller("Alice", 4L))
        assertEquals("Alice", activityStore.verifiedBindings().single().localLabel)
        assertTrue(listenerStore.isAllowedDigest(DIGEST_A))
    }

    @Test
    fun alreadyOpenStoreSeesBindingDeletionAndFilteringDisabled() {
        val preferences = InMemorySharedPreferences()
        val first = WeChatSettingsStore(preferences, schemaVersion = 1)
        first.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 1L)
        first.recordCandidate(WeChatIdentityCandidate.Supported(DIGEST_A), 2L)
        assertTrue(first.bindRecentCaller("Alice", 3L))
        assertTrue(first.setFilteringEnabled(true))
        val details = WeChatSettingsStore(preferences, schemaVersion = 1)

        assertTrue(details.removeBinding(DIGEST_A))

        assertFalse(first.isFilteringEnabled())
        assertFalse(first.isAllowedDigest(DIGEST_A))
        assertTrue(first.verifiedBindings().isEmpty())
    }

    @Test
    fun matchingSecondObservationMakesOnlyThatDigestBindable() {
        val registry = WeChatBindingRegistry(schemaVersion = 1)
        registry.record(DIGEST_A, 100L)
        registry.record(DIGEST_B, 101L)
        registry.record(DIGEST_A, 102L)

        assertEquals(DIGEST_A, registry.bindableDigest())
        assertNotEquals(DIGEST_B, registry.bindableDigest())
    }

    @Test
    fun filteringCannotEnableWithoutVerifiedBinding() {
        val registry = WeChatBindingRegistry(schemaVersion = 1)

        assertFalse(registry.setFilteringEnabled(true))
    }

    @Test
    fun schemaChangeInvalidatesBindingsAndDisablesFiltering() {
        val registry = WeChatBindingRegistry(schemaVersion = 1)
        registry.record(DIGEST_A, 9L)
        registry.record(DIGEST_A, 10L)
        registry.addVerified(DIGEST_A, "Alice", verifiedAt = 10L)
        registry.setFilteringEnabled(true)

        registry.invalidateForSchemaVersion(currentSchemaVersion = 2)

        assertEquals(emptyList<WeChatCallerBinding>(), registry.bindings())
        assertFalse(registry.isFilteringEnabled())
    }

    @Test
    fun blankTrimmedLabelCannotCreateBinding() {
        val registry = WeChatBindingRegistry(schemaVersion = 1)
        registry.record(DIGEST_A, 9L)
        registry.record(DIGEST_A, 10L)

        registry.addVerified(DIGEST_A, "   ", verifiedAt = 10L)

        assertEquals(emptyList<WeChatCallerBinding>(), registry.bindings())
    }

    @Test
    fun verifiedDigestIsAllowedWhileUnboundDigestIsNot() {
        val registry = WeChatBindingRegistry(schemaVersion = 1)
        registry.record(DIGEST_A, 9L)
        registry.record(DIGEST_A, 10L)
        registry.addVerified(DIGEST_A, " Alice ", verifiedAt = 10L)

        assertTrue(registry.isAllowed(DIGEST_A))
        assertFalse(registry.isAllowed(DIGEST_B))
        assertEquals("Alice", registry.bindings().single().localLabel)
    }

    @Test
    fun arbitraryDigestCannotBypassTheObservationGate() {
        val registry = WeChatBindingRegistry(schemaVersion = 1)

        registry.addVerified(DIGEST_A, "Alice", verifiedAt = 10L)

        assertEquals(emptyList<WeChatCallerBinding>(), registry.bindings())
    }

    @Test
    fun settingsStoreRejectsMalformedDigestsAndAcceptsSha256Digest() {
        val preferences = InMemorySharedPreferences()
        val store = WeChatSettingsStore(preferences = preferences, schemaVersion = 1)
        val malformed = WeChatIdentityCandidate.Supported("Alice")
        val valid = WeChatIdentityCandidate.Supported(DIGEST_A)

        store.recordCandidate(malformed, 1L)
        store.recordCandidate(malformed, 2L)
        assertFalse(store.bindRecentCaller("Alice", verifiedAt = 3L))
        assertFalse(store.isAllowedDigest("Alice"))

        store.recordCandidate(valid, 4L)
        store.recordCandidate(valid, 5L)
        assertTrue(store.bindRecentCaller("Alice", verifiedAt = 6L))
        assertTrue(store.isAllowedDigest(DIGEST_A))
        assertFalse(store.isAllowedDigest("Alice"))

        val reloaded = WeChatSettingsStore(preferences = preferences, schemaVersion = 1)
        assertEquals(listOf(DIGEST_A), reloaded.verifiedBindings().map(WeChatCallerBinding::idDigest))
    }

    @Test
    fun malformedDigestCannotBecomeBindable() {
        val registry = WeChatBindingRegistry(schemaVersion = 1)

        registry.record("Alice", 1L)
        registry.record("Alice", 2L)

        assertNull(registry.bindableDigest())
    }

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

    private class Editor(private val values: MutableMap<String, Any>) : SharedPreferences.Editor {
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
        override fun commit(): Boolean = true.also { apply() }
        override fun apply() {
            removals.forEach(values::remove)
            values.putAll(updates)
        }
    }

    private companion object {
        val DIGEST_A = "a".repeat(64)
        val DIGEST_B = "b".repeat(64)
    }
}
