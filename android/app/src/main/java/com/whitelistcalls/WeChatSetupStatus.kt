package com.whitelistcalls

import android.content.Context
import android.content.SharedPreferences
import com.whitelistcalls.externalcalls.WeChatCallerIdentity
import com.whitelistcalls.externalcalls.WeChatIdentityCandidate
import java.util.Base64
import java.lang.ref.WeakReference
import com.whitelistcalls.externalcalls.ExternalCallProvider

data class WeChatSetupStatus(val isReady: Boolean, val label: String, val detail: String) {
    companion object {
        fun from(
            notificationAccessGranted: Boolean,
            filteringEnabled: Boolean,
            hasVerifiedBinding: Boolean,
        ): WeChatSetupStatus = when {
            !notificationAccessGranted -> WeChatSetupStatus(false, "Access required", "Grant notification access to detect incoming WeChat calls.")
            !hasVerifiedBinding -> WeChatSetupStatus(false, "Binding required", "Receive two separate calls from the same caller, then bind their verified identity below.")
            !filteringEnabled -> WeChatSetupStatus(false, "Filtering off", "Turn on Filter WeChat calls to use your verified callers.")
            else -> WeChatSetupStatus(true, "Ready", "WeChat filtering is on for your verified callers. Main protection must also be on.")
        }
    }
}

// Only validated digests live here. Android shares each named SharedPreferences instance
// across this process, so listener and activity stores see the same bounded session.
private class WeChatEnrollmentSession(val schemaVersion: Int) {
    private val observations = ArrayDeque<Pair<String?, Long>>()
    @Volatile var diagnosticStatus = WeChatDiagnosticStatus.MISSING
        private set

    @Synchronized
    fun record(digest: String?, observedAt: Long, status: WeChatDiagnosticStatus) {
        diagnosticStatus = status
        observations.addLast(digest?.takeIf(::isValidWeChatDigest) to observedAt)
        if (observations.size > 64) observations.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Pair<String?, Long>> = observations.toList()
}

enum class WeChatDiagnosticStatus { SUPPORTED, NAME_ONLY, MISSING, UNSUPPORTED }

data class WeChatRecentCandidate(val digest: String, val observationCount: Int, val canBind: Boolean) {
    val fingerprint: String get() = digest.take(8) + "…"
}

data class WeChatCallerBinding(
    val idDigest: String,
    val localLabel: String,
    val verificationVersion: Int,
    val verifiedAt: Long,
)

class WeChatBindingRegistry(private val schemaVersion: Int) {
    private val observations = ArrayDeque<Observation>()
    private val observationCounts = mutableMapOf<String, Int>()
    private val verifiedBindings = linkedMapOf<String, WeChatCallerBinding>()
    private var filteringEnabled = false
    private var latestDigest: String? = null

    fun record(digest: String, observedAt: Long) {
        latestDigest = digest.takeIf(::isValidWeChatDigest)
        if (!isValidWeChatDigest(digest)) return
        observations.addLast(Observation(digest, observedAt))
        observationCounts[digest] = (observationCounts[digest] ?: 0) + 1
        if (observations.size > MAX_SESSION_OBSERVATIONS) {
            val expired = observations.removeFirst()
            observationCounts.compute(expired.digest) { _, count ->
                (count ?: 1).minus(1).takeIf { it > 0 }
            }
        }
    }

    fun recentCandidate(): WeChatRecentCandidate? = latestDigest?.let { digest ->
        val count = observationCounts[digest].orZero()
        WeChatRecentCandidate(digest, count, count >= REQUIRED_OBSERVATIONS && digest !in verifiedBindings)
    }

    fun bindableDigest(): String? = recentCandidate()?.takeIf { it.canBind }?.digest

    fun addVerified(digest: String, label: String, verifiedAt: Long): Boolean {
        if (digest != bindableDigest()) return false
        return restoreVerified(
            WeChatCallerBinding(
                idDigest = digest,
                localLabel = label,
                verificationVersion = schemaVersion,
                verifiedAt = verifiedAt,
            ),
        )
    }

    internal fun restoreVerified(binding: WeChatCallerBinding): Boolean {
        if (binding.verificationVersion != schemaVersion || !isValidWeChatDigest(binding.idDigest)) {
            return false
        }
        val digest = binding.idDigest
        val label = binding.localLabel
        val localLabel = label.trim()
        if (localLabel.isBlank()) return false
        verifiedBindings[digest] = WeChatCallerBinding(
            idDigest = digest,
            localLabel = localLabel,
            verificationVersion = schemaVersion,
            verifiedAt = binding.verifiedAt,
        )
        return true
    }

    fun bindings(): List<WeChatCallerBinding> = verifiedBindings.values
        .sortedByDescending(WeChatCallerBinding::verifiedAt)

    fun isAllowed(digest: String): Boolean =
        isValidWeChatDigest(digest) && digest in verifiedBindings

    fun setFilteringEnabled(enabled: Boolean): Boolean {
        filteringEnabled = enabled && verifiedBindings.isNotEmpty()
        return filteringEnabled
    }

    fun isFilteringEnabled(): Boolean = filteringEnabled

    fun invalidateForSchemaVersion(currentSchemaVersion: Int) {
        if (schemaVersion != currentSchemaVersion) {
            verifiedBindings.clear()
            filteringEnabled = false
        }
    }

    internal fun removeVerified(digest: String): Boolean = verifiedBindings.remove(digest) != null

    private data class Observation(val digest: String, val observedAt: Long)

    private companion object {
        const val REQUIRED_OBSERVATIONS = 2
        const val MAX_SESSION_OBSERVATIONS = 64
    }
}

class WeChatSettingsStore internal constructor(
    private val preferences: SharedPreferences,
    private val schemaVersion: Int = WeChatCallerIdentity.schemaVersion,
) {
    private var registry = WeChatBindingRegistry(schemaVersion)
    private val enrollmentSession = synchronized(enrollmentSessions) {
        enrollmentSessions.removeAll { it.first.get() == null }
        enrollmentSessions.firstOrNull {
            it.first.get() === preferences && it.second.schemaVersion == schemaVersion
        }?.second ?: WeChatEnrollmentSession(schemaVersion).also {
            enrollmentSessions.add(WeakReference(preferences) to it)
        }
    }

    constructor(context: Context) : this(
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
    )

    fun recordCandidate(candidate: WeChatIdentityCandidate, observedAt: Long) {
        val status = when (candidate) {
            is WeChatIdentityCandidate.Supported -> if (isValidWeChatDigest(candidate.digest)) {
                WeChatDiagnosticStatus.SUPPORTED
            } else {
                WeChatDiagnosticStatus.UNSUPPORTED
            }
            WeChatIdentityCandidate.NameOnly -> WeChatDiagnosticStatus.NAME_ONLY
            WeChatIdentityCandidate.Missing -> WeChatDiagnosticStatus.MISSING
        }
        val digest = (candidate as? WeChatIdentityCandidate.Supported)?.digest
        enrollmentSession.record(digest, observedAt, status)
    }

    fun diagnosticStatus(): WeChatDiagnosticStatus = enrollmentSession.diagnosticStatus

    fun recordUnsupportedNotification() {
        enrollmentSession.record(null, 0L, WeChatDiagnosticStatus.UNSUPPORTED)
    }

    fun setDirectDeclineUnavailable(unavailable: Boolean) {
        directDeclineUnavailable = unavailable
    }

    fun isDirectDeclineUnavailable(): Boolean = directDeclineUnavailable

    fun status(notificationAccessGranted: Boolean): WeChatSetupStatus = WeChatSetupStatus.from(
        notificationAccessGranted = notificationAccessGranted,
        filteringEnabled = isFilteringEnabled(),
        hasVerifiedBinding = isFilteringEligible(),
    )

    fun recentCandidate(): WeChatRecentCandidate? {
        ensureRegistryLoaded()
        return registry.recentCandidate()
    }

    fun bindRecentCaller(label: String, verifiedAt: Long, expectedDigest: String? = null): Boolean {
        ensureRegistryLoaded()
        val digest = registry.bindableDigest() ?: return false
        if (expectedDigest != null && digest != expectedDigest) return false
        if (label.trim().isBlank()) return false
        if (!registry.addVerified(digest, label, verifiedAt)) return false
        persistBindings()
        return true
    }

    fun verifiedBindings(): List<WeChatCallerBinding> {
        ensureRegistryLoaded()
        return registry.bindings()
    }

    fun renameBinding(digest: String, label: String): Boolean {
        ensureRegistryLoaded()
        val existing = registry.bindings().firstOrNull { it.idDigest == digest } ?: return false
        if (label.trim().isBlank()) return false
        if (!registry.restoreVerified(existing.copy(localLabel = label))) return false
        persistBindings()
        return true
    }

    fun removeBinding(digest: String): Boolean {
        ensureRegistryLoaded()
        if (!registry.removeVerified(digest)) return false
        if (registry.bindings().isEmpty()) registry.setFilteringEnabled(false)
        persistBindings()
        persistFilteringState()
        AllowedCallVibrationController.cancelProviderAlert(ExternalCallProvider.WECHAT, digest)
        return true
    }

    fun isAllowedDigest(digest: String): Boolean {
        ensureRegistryLoaded()
        if (!isValidWeChatDigest(digest)) return false
        return registry.isAllowed(digest)
    }

    fun isFilteringEligible(): Boolean {
        ensureRegistryLoaded()
        return registry.bindings().isNotEmpty()
    }

    fun isFilteringEnabled(): Boolean {
        ensureRegistryLoaded()
        return registry.isFilteringEnabled()
    }

    fun setFilteringEnabled(enabled: Boolean): Boolean {
        ensureRegistryLoaded()
        val filteringEnabled = registry.setFilteringEnabled(enabled)
        persistFilteringState()
        if (!filteringEnabled) AllowedCallVibrationController.cancelProviderAlert(ExternalCallProvider.WECHAT)
        return filteringEnabled
    }

    private fun ensureRegistryLoaded() {
        // Other activity/listener instances may have changed bindings or filtering.
        registry = WeChatBindingRegistry(schemaVersion)
        enrollmentSession.snapshot().forEach { (digest, observedAt) -> registry.record(digest.orEmpty(), observedAt) }

        val encodedBindings = preferences.getStringSet(KEY_VERIFIED_BINDINGS, emptySet())!!
        val storedBindings = encodedBindings
            .mapNotNull(::decodeBinding)
        if (storedBindings.any { it.verificationVersion != schemaVersion }) {
            preferences.edit()
                .remove(KEY_VERIFIED_BINDINGS)
                .putBoolean(KEY_FILTERING_ENABLED, false)
                .apply()
            return
        }

        storedBindings.forEach { binding ->
            registry.restoreVerified(binding)
        }
        registry.setFilteringEnabled(preferences.getBoolean(KEY_FILTERING_ENABLED, false))
        if (storedBindings.size != encodedBindings.size) {
            persistBindings()
            persistFilteringState()
        }
    }

    private fun persistBindings() {
        preferences.edit()
            .putStringSet(
                KEY_VERIFIED_BINDINGS,
                registry.bindings().filter { isValidWeChatDigest(it.idDigest) }.map(::encodeBinding).toSet(),
            )
            .apply()
    }

    private fun persistFilteringState() {
        preferences.edit()
            .putBoolean(KEY_FILTERING_ENABLED, registry.isFilteringEnabled())
            .apply()
    }

    private fun encodeBinding(binding: WeChatCallerBinding): String {
        check(isValidWeChatDigest(binding.idDigest))
        return listOf(
            binding.idDigest,
            binding.localLabel,
            binding.verificationVersion.toString(),
            binding.verifiedAt.toString(),
        ).joinToString(".") { value ->
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        }
    }

    private fun decodeBinding(encoded: String): WeChatCallerBinding? = runCatching {
        val parts = encoded.split('.', limit = 4)
        if (parts.size != 4) return@runCatching null
        WeChatCallerBinding(
            idDigest = decodePart(parts[0]),
            localLabel = decodePart(parts[1]),
            verificationVersion = decodePart(parts[2]).toInt(),
            verifiedAt = decodePart(parts[3]).toLong(),
        ).takeIf { binding ->
            isValidWeChatDigest(binding.idDigest) && binding.localLabel.trim().isNotBlank()
        }
    }.getOrNull()

    private fun decodePart(encoded: String): String =
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)

    private companion object {
        const val PREFERENCES = "wechat_settings"
        const val KEY_VERIFIED_BINDINGS = "verified_bindings"
        const val KEY_FILTERING_ENABLED = "filtering_enabled"
        val enrollmentSessions = mutableListOf<Pair<WeakReference<SharedPreferences>, WeChatEnrollmentSession>>()
        @Volatile private var directDeclineUnavailable = false
    }
}

private fun Int?.orZero(): Int = this ?: 0

private val weChatDigestPattern = Regex("[0-9a-f]{64}")

private fun isValidWeChatDigest(digest: String): Boolean = weChatDigestPattern.matches(digest)
