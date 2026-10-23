package com.whitelistcalls

import android.content.SharedPreferences
import com.whitelistcalls.externalcalls.CallDecision
import com.whitelistcalls.externalcalls.CallerIdentity
import com.whitelistcalls.externalcalls.decide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppWhitelistModesTest {
    @Test
    fun defaultsExistingInstallationsToSharedMode() = assertEquals(
        WhatsAppWhitelistMode.SHARED,
        modeFrom(null),
    )

    @Test
    fun copyReplacesOldCustomNumbers() {
        val customStore = newCustomStore()

        customStore.replaceCustomNumbers(setOf("+31600000000"))
        customStore.replaceCustomNumbers(setOf("+31612345678"))

        assertEquals(setOf("+31612345678"), customStore.customNumbers())
    }

    @Test
    fun mergeAddsOnlyMissingSharedNumbers() {
        val customStore = newCustomStore()
        customStore.replaceCustomNumbers(setOf("+31612345678"))

        customStore.mergeSharedNumbers(setOf("+31600000000", "+31612345678"))

        assertEquals(
            setOf("+31612345678", "+31600000000"),
            customStore.customNumbers(),
        )
    }

    @Test
    fun filteringIsEffectiveOnlyWhenMasterAndWhatsAppFilteringAreEnabled() {
        assertTrue(isWhatsAppFilteringEffective(masterEnabled = true, whatsappEnabled = true))
        assertFalse(isWhatsAppFilteringEffective(masterEnabled = false, whatsappEnabled = true))
        assertFalse(isWhatsAppFilteringEffective(masterEnabled = true, whatsappEnabled = false))
        assertFalse(isWhatsAppFilteringEffective(masterEnabled = false, whatsappEnabled = false))
    }

    @Test
    fun masterOffLeavesSavedWhatsAppToggleUntouched() {
        val savedSubToggle = true
        assertFalse(isWhatsAppFilteringEffective(masterEnabled = false, whatsappEnabled = savedSubToggle))
        assertTrue(savedSubToggle)
    }

    @Test
    fun masterOffDisablesWhatsAppSwitchWithoutChangingItsSavedCheckedState() {
        val state = whatsAppFilteringToggleState(
            masterEnabled = false,
            savedEnabled = true,
        )

        assertTrue(state.checked)
        assertFalse(state.enabled)
    }

    @Test
    fun rejectsInvalidCustomNumber() = assertFalse(newCustomStore().addCustomNumber("invalid"))

    @Test
    fun storesValidCustomNumberInNormalizedForm() {
        val customStore = newCustomStore()

        assertTrue(customStore.addCustomNumber("+31 6 12345678"))

        assertEquals(setOf("+31612345678"), customStore.customNumbers())
    }

    @Test
    fun rejectsDuplicateCustomNumber() {
        val customStore = newCustomStore()
        assertTrue(customStore.addCustomNumber("+31612345678"))

        assertFalse(customStore.addCustomNumber("+31 6 12345678"))
    }

    @Test
    fun customModeAllowsFuzzyEquivalentNumberPersistedInCustomStore() {
        val customStore = newCustomStore()
        customStore.replaceCustomNumbers(setOf("+31612345678"))
        val customMembership = customStore.customMembership(TEST_NORMALIZE)

        assertEquals(
            CallDecision.ALLOW,
            decide(
                filteringEnabled = true,
                identity = CallerIdentity.Phone("0612345678"),
                isAllowed = customMembership::contains,
            ),
        )
    }

    @Test
    fun customModeRejectsSharedOnlyNumberWhenAbsentFromCustomSet() {
        val customStore = newCustomStore()
        customStore.replaceCustomNumbers(setOf("+31600000000"))
        val customMembership = customStore.customMembership(TEST_NORMALIZE)

        assertEquals(
            CallDecision.REJECT,
            decide(
                filteringEnabled = true,
                identity = CallerIdentity.Phone("+31612345678"),
                isAllowed = customMembership::contains,
            ),
        )
    }

    @Test
    fun removesCustomNumber() {
        val customStore = newCustomStore()
        customStore.replaceCustomNumbers(setOf("+31612345678"))

        customStore.removeCustomNumber("+31612345678")

        assertTrue(customStore.customNumbers().isEmpty())
    }

    @Test
    fun removesCopiedNationalFormatNumberByItsPersistedKey() {
        val customStore = newCustomStore()
        customStore.replaceCustomNumbers(setOf("0612345678"))

        customStore.removeCustomNumber("0612345678")

        assertTrue(customStore.customNumbers().isEmpty())
    }

    private fun newCustomStore(): WhatsAppSettingsStore = WhatsAppSettingsStore(
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
        private var clearRequested = false

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

        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearRequested) values.clear()
            removals.forEach(values::remove)
            values.putAll(updates)
        }
    }

    private companion object {
        val TEST_NORMALIZE: (String?) -> String? = { raw ->
            raw?.filter { it.isDigit() || it == '+' }?.takeIf(String::isNotBlank)
        }
    }
}
