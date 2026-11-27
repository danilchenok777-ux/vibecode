package org.telegram.messenger.godhead

import android.content.Context
import java.io.File
import java.io.OutputStream

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
        return withSession(api::currentUser)
    }

    fun connectRealtime(listener: GodheadRealtimeClient.Listener): GodheadRealtimeClient {
        val token = sessions.readAccessToken()
            ?: throw IllegalStateException("No GODHEAD session is stored on this device")
        val forwardingListener = object : GodheadRealtimeClient.Listener {
            override fun onConnected() = listener.onConnected()
            override fun onMessage(message: GodheadRealtimeMessage) = listener.onMessage(message)
            override fun onDisconnected(code: Int, reason: String) {
                if (code == 4401 || code == 4403) sessions.clear()
                listener.onDisconnected(code, reason)
            }
            override fun onError(error: Throwable) = listener.onError(error)
            override fun onStateEvent(type: String, data: org.json.JSONObject) =
                listener.onStateEvent(type, data)
        }
        return GodheadRealtimeClient(apiBaseUrl, token, forwardingListener).also { it.connect() }
    }

    suspend fun searchUsers(query: String, limit: Int = 8): List<GodheadPublicUser> =
        withSession { api.searchUsers(it, query, limit) }

    suspend fun openDirectConversation(targetUserId: Long): GodheadConversation =
        withSession { api.openDirectConversation(it, targetUserId) }

    suspend fun createGroupConversation(title: String, memberIds: List<Long>): GodheadConversation =
        withSession { api.createGroupConversation(it, title, memberIds) }

    suspend fun listConversations(limit: Int = 50): List<GodheadConversation> =
        withSession { api.listConversations(it, limit) }

    suspend fun sendMessage(conversationId: Long, content: String): GodheadMessage =
        withSession { api.sendMessage(it, conversationId, content) }

    suspend fun uploadAttachment(
        conversationId: Long,
        file: File,
        filename: String,
        contentType: String,
        caption: String = ""
    ): GodheadMessage = withSession {
        api.uploadAttachment(it, conversationId, file, filename, contentType, caption)
    }

    suspend fun downloadAttachment(
        conversationId: Long,
        attachmentId: Long,
        output: OutputStream
    ): GodheadAttachment = withSession {
        api.downloadAttachment(it, conversationId, attachmentId, output)
    }

    suspend fun acknowledgeDelivery(
        conversationId: Long,
        messageId: Long
    ): GodheadDeliveryState = withSession {
        api.acknowledgeDelivery(it, conversationId, messageId)
    }

    suspend fun markConversationRead(
        conversationId: Long,
        messageId: Long
    ): GodheadReadState = withSession {
        api.markConversationRead(it, conversationId, messageId)
    }

    suspend fun listReadStates(conversationId: Long): List<GodheadReadState> =
        withSession { api.listReadStates(it, conversationId) }

    suspend fun listDeliveryStates(
        conversationId: Long,
        messageId: Long
    ): List<GodheadDeliveryState> = withSession {
        api.listDeliveryStates(it, conversationId, messageId)
    }

    suspend fun listMessages(
        conversationId: Long,
        beforeId: Long? = null,
        limit: Int = 50
    ): GodheadMessagePage =
        withSession { api.listMessages(it, conversationId, beforeId, limit) }

    suspend fun listSessions(): List<GodheadRemoteSession> =
        withSession(api::listSessions)

    suspend fun revokeSession(sessionId: Long) =
        withSession { api.revokeSession(it, sessionId) }

    private fun requireAccessToken(): String =
        sessions.readAccessToken()
            ?: throw IllegalStateException("No GODHEAD session is stored on this device")

    suspend fun signOut() {
        val token = sessions.readAccessToken()
        try {
            if (token != null) {
                api.logout(token)
            }
        } catch (error: GodheadApiException) {
            if (error.statusCode != 401) throw error
        } finally {
            // Local sign-out must still complete if the device is offline.
            sessions.clear()
        }
    }

    fun hasLocalSession(): Boolean = sessions.readAccessToken() != null

    private suspend fun <T> withSession(operation: suspend (String) -> T): T {
        val token = sessions.readAccessToken()
            ?: throw IllegalStateException("No GODHEAD session is stored on this device")
        try {
            return operation(token)
        } catch (error: GodheadApiException) {
            if (error.statusCode == 401) {
                sessions.clear()
                throw IllegalStateException("GODHEAD session expired. Sign in again.", error)
            }
            throw error
        }
    }
}
