package com.whitelistcalls.externalcalls

import java.security.MessageDigest

data class WeChatIdentityObservation(
    val personUri: String? = null,
    val personKey: String? = null,
    val shortcutId: String? = null,
    val displayName: String? = null,
)

sealed interface WeChatIdentityCandidate {
    data class Supported(val digest: String) : WeChatIdentityCandidate
    data object NameOnly : WeChatIdentityCandidate
    data object Missing : WeChatIdentityCandidate
}

object WeChatCallerIdentity {
    const val schemaVersion = 1

    fun extract(value: WeChatIdentityObservation): WeChatIdentityCandidate {
        val canonical = sequenceOf(
            value.personUri?.takeIf(String::isNotBlank)?.let { "person-uri:$it" },
            value.personKey?.takeIf(String::isNotBlank)?.let { "person-key:$it" },
            value.shortcutId?.takeIf(String::isNotBlank)?.let { "shortcut:$it" },
        ).firstOrNull()
        return when {
            canonical != null -> WeChatIdentityCandidate.Supported(digest(canonical))
            !value.displayName.isNullOrBlank() -> WeChatIdentityCandidate.NameOnly
            else -> WeChatIdentityCandidate.Missing
        }
    }

    fun digest(canonical: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
