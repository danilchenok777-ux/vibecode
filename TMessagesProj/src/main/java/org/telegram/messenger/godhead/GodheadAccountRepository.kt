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

    fun signOut() {
        sessions.clear()
    }

    fun hasLocalSession(): Boolean = sessions.readAccessToken() != null
}
