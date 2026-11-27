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
    val createdAt: String,
    val attachment: GodheadAttachment? = null
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
        fun onReadStateUpdated(state: GodheadReadState)
        fun onDeliveryStateUpdated(state: GodheadDeliveryState, conversationId: Long)
        fun onDisconnected(code: Int, reason: String)
        fun onError(error: Throwable)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val request: Request
    @Volatile private var webSocket: WebSocket? = null

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
        if (webSocket != null) return
        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post { listener.onConnected() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = JSONObject(text)
                    when (event.optString("type")) {
                        "message.created" -> {
                            val data = event.getJSONObject("data")
                            val attachmentJson = data.optJSONObject("attachment")
                            val attachment = attachmentJson?.let {
                                GodheadAttachment(
                                    id = it.getLong("id"),
                                    filename = it.getString("filename"),
                                    contentType = it.getString("content_type"),
                                    sizeBytes = it.getLong("size_bytes")
                                )
                            }
                            val message = GodheadRealtimeMessage(
                                id = data.getLong("id"),
                                conversationId = data.getLong("conversation_id"),
                                senderId = data.getLong("sender_id"),
                                content = data.getString("content"),
                                createdAt = data.optString("created_at"),
                                attachment = attachment
                            )
                            mainHandler.post { listener.onMessage(message) }
                        }
                        "message.delivered" -> {
                            val data = event.getJSONObject("data")
                            val state = GodheadDeliveryState(
                                messageId = data.getLong("message_id"),
                                userId = data.getLong("user_id"),
                                deliveredAt = if (data.isNull("delivered_at")) null
                                    else data.optString("delivered_at")
                            )
                            val conversationId = data.getLong("conversation_id")
                            mainHandler.post {
                                listener.onDeliveryStateUpdated(state, conversationId)
                            }
                        }
                        "read.state.updated" -> {
                            val data = event.getJSONObject("data")
                            val state = GodheadReadState(
                                conversationId = data.getLong("conversation_id"),
                                userId = data.getLong("user_id"),
                                lastReadMessageId = if (data.isNull("last_read_message_id")) null
                                    else data.getLong("last_read_message_id"),
                                updatedAt = if (data.isNull("updated_at")) null
                                    else data.optString("updated_at")
                            )
                            mainHandler.post { listener.onReadStateUpdated(state) }
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
                this@GodheadRealtimeClient.webSocket = null
                mainHandler.post { listener.onDisconnected(code, reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@GodheadRealtimeClient.webSocket = null
                mainHandler.post { listener.onError(t) }
            }
        })
    }

    fun sendPing(): Boolean = webSocket?.send("ping") ?: false

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }
}
