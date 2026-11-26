package org.telegram.messenger.godhead

import android.content.Context

/**
 * Coordinates GODHEAD API authentication with an encrypted, device-local session.
 * This repository is separate from Telegram's MTProto account/session manager.
 */
class GodheadAccountRepository(
    context: Context,
    private val apiBaseUrl: String
) {
    private val api = GodheadApiClient(apiBaseUrl)
    private val sessions = GodheadSessionStore(context)

    suspend fun register(
        email: String,
        username: String,
        password: String,
        displayName: String
    ): GodheadUser {
        val session = api.register(email, username, password, displayName)
        sessions.saveAccessToken(session.accessToken)
        return session.user
    }

    suspend fun login(email: String, password: String): GodheadUser {
        val session = api.login(email, password)
        sessions.saveAccessToken(session.accessToken)
        return session.user
    }

    suspend fun currentUser(): GodheadUser {
        val token = sessions.readAccessToken()
            ?: throw IllegalStateException("No GODHEAD session is stored on this device")
        return api.currentUser(token)
    }

    fun connectRealtime(listener: GodheadRealtimeClient.Listener): GodheadRealtimeClient {
        val token = sessions.readAccessToken()
            ?: throw IllegalStateException("No GODHEAD session is stored on this device")
        return GodheadRealtimeClient(apiBaseUrl, token, listener).also { it.connect() }
    }

    suspend fun searchUsers(query: String, limit: Int = 8): List<GodheadPublicUser> =
        api.searchUsers(requireAccessToken(), query, limit)

    suspend fun openDirectConversation(targetUserId: Long): GodheadConversation =
        api.openDirectConversation(requireAccessToken(), targetUserId)

    suspend fun createGroupConversation(title: String, memberIds: List<Long>): GodheadConversation =
        api.createGroupConversation(requireAccessToken(), title, memberIds)

    suspend fun listConversations(limit: Int = 50): List<GodheadConversation> =
        api.listConversations(requireAccessToken(), limit)

    suspend fun sendMessage(conversationId: Long, content: String): GodheadMessage =
        api.sendMessage(requireAccessToken(), conversationId, content)

    suspend fun listMessages(
        conversationId: Long,
        beforeId: Long? = null,
        limit: Int = 50
    ): GodheadMessagePage =
        api.listMessages(requireAccessToken(), conversationId, beforeId, limit)

    private fun requireAccessToken(): String =
        sessions.readAccessToken()
            ?: throw IllegalStateException("No GODHEAD session is stored on this device")

    suspend fun signOut() {
        val token = sessions.readAccessToken()
        try {
            if (token != null) {
                api.logout(token)
            }
        } finally {
            // Local sign-out must still complete if the device is offline.
            sessions.clear()
        }
    }

    fun hasLocalSession(): Boolean = sessions.readAccessToken() != null
}
