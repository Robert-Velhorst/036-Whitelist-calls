package com.whitelistcalls.externalcalls

private const val SESSION_TTL_MILLIS = 60_000L

class BlockedCallSessionStore {
    private val sessions = mutableMapOf<SessionId, Long>()

    @Synchronized
    fun shouldAttemptReject(
        provider: ExternalCallProvider,
        notificationKey: String,
        nowMillis: Long,
    ): Boolean {
        sessions.entries.removeIf { nowMillis - it.value >= SESSION_TTL_MILLIS }

        val sessionId = SessionId(provider, hashNotificationKey(notificationKey))
        if (sessions.containsKey(sessionId)) return false

        sessions[sessionId] = nowMillis
        return true
    }

    @Synchronized
    fun remove(provider: ExternalCallProvider, notificationKey: String) {
        sessions.remove(SessionId(provider, hashNotificationKey(notificationKey)))
    }

    private data class SessionId(
        val provider: ExternalCallProvider,
        val notificationKeyHash: String,
    )
}
