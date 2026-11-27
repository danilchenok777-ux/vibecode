package org.telegram.messenger.godhead

import android.os.Handler
import android.os.Looper
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GodheadRealtimeMessage(
    val id: Long,
    val conversationId: Long,
    val senderId: Long,
    val content: String,
    val createdAt: String
)

/**
 * Receives server events for the signed-in GODHEAD account.
 * Message creation stays on GodheadApiClient's authenticated HTTP endpoint.
 */
class GodheadRealtimeClient(
    baseUrl: String,
    accessToken: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onMessage(message: GodheadRealtimeMessage)
        fun onDisconnected(code: Int, reason: String)
        fun onError(error: Throwable)
        fun onStateEvent(type: String, data: JSONObject) = Unit
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val request: Request
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var closedByClient = true
    private var reconnectDelayMs = 1_000L
    private val reconnectRunnable = Runnable { openSocket() }
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val socket = webSocket ?: return
            if (!socket.send("ping")) {
                scheduleReconnect()
                return
            }
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    init {
        require(accessToken.isNotBlank()) { "GODHEAD access token is required" }
        val parsed = baseUrl.trim().trimEnd('/').toHttpUrl()
        require(parsed.scheme == "https" || parsed.host == "10.0.2.2" ||
            parsed.host == "localhost" || parsed.host == "127.0.0.1") {
            "GODHEAD realtime requires HTTPS outside local development"
        }
        val endpoint = parsed.newBuilder().encodedPath("/v1/ws").query(null).build()
        request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $accessToken")
            .build()
    }

    fun connect() {
        closedByClient = false
        openSocket()
    }

    private fun openSocket() {
        if (closedByClient || webSocket != null) return
        mainHandler.removeCallbacks(reconnectRunnable)
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closedByClient) {
                    webSocket.close(1000, "Client disconnected")
                    return
                }
                this@GodheadRealtimeClient.webSocket = webSocket
                reconnectDelayMs = 1_000L
                mainHandler.removeCallbacks(reconnectRunnable)
                mainHandler.removeCallbacks(heartbeatRunnable)
                mainHandler.post {
                    listener.onConnected()
                    heartbeatRunnable.run()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = JSONObject(text)
                    if (event.optString("type") == "message.created") {
                        val data = event.getJSONObject("data")
                        val message = GodheadRealtimeMessage(
                            id = data.getLong("id"),
                            conversationId = data.getLong("conversation_id"),
                            senderId = data.getLong("sender_id"),
                            content = data.getString("content"),
                            createdAt = data.optString("created_at")
                        )
                        mainHandler.post { listener.onMessage(message) }
                    } else {
                        val type = event.optString("type")
                        if (type == "message.delivered" || type == "read.state.updated") {
                            val data = event.optJSONObject("data") ?: JSONObject()
                            mainHandler.post { listener.onStateEvent(type, data) }
                        }
                    }
                } catch (error: Exception) {
                    mainHandler.post { listener.onError(error) }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                clearSocket(webSocket)
                mainHandler.post { listener.onDisconnected(code, reason) }
                if (code == 4401 || code == 4403) {
                    closedByClient = true
                } else {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                clearSocket(webSocket)
                mainHandler.post { listener.onError(t) }
                scheduleReconnect()
            }
        })
        webSocket = socket
    }

    private fun clearSocket(socket: WebSocket) {
        if (webSocket === socket) webSocket = null
        mainHandler.removeCallbacks(heartbeatRunnable)
    }

    private fun scheduleReconnect() {
        if (closedByClient) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    fun sendPing(): Boolean = webSocket?.send("ping") ?: false

    fun disconnect() {
        closedByClient = true
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(heartbeatRunnable)
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 20_000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
