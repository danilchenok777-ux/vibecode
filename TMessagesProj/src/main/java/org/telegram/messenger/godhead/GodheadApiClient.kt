package org.telegram.messenger.godhead

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

data class GodheadUser(
    val id: Long,
    val email: String,
    val username: String,
    val displayName: String,
    val createdAt: String
)

data class GodheadAuthSession(
    val accessToken: String,
    val tokenType: String,
    val expiresInSeconds: Long,
    val user: GodheadUser
)

data class GodheadPublicUser(
    val id: Long,
    val username: String,
    val displayName: String
)

data class GodheadMessage(
    val id: Long,
    val conversationId: Long,
    val senderId: Long,
    val content: String,
    val createdAt: String,
    val attachment: GodheadAttachment? = null
)

data class GodheadAttachment(
    val id: Long,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long
)

data class GodheadAttachmentDownload(
    val attachment: GodheadAttachment,
    val url: String,
    val expiresInSeconds: Long
)

data class GodheadReadState(
    val conversationId: Long,
    val userId: Long,
    val lastReadMessageId: Long?
)

data class GodheadDeliveryState(
    val messageId: Long,
    val userId: Long,
    val deliveredAt: String?
)

data class GodheadConversation(
    val id: Long,
    val kind: String,
    val title: String?,
    val createdBy: Long,
    val memberIds: List<Long>,
    val createdAt: String,
    val updatedAt: String,
    val lastMessage: GodheadMessage?,
    val unreadCount: Int
)

data class GodheadMessagePage(
    val items: List<GodheadMessage>,
    val nextBeforeId: Long?
)

data class GodheadRemoteSession(
    val id: Long,
    val createdAt: String,
    val expiresAt: String,
    val isCurrent: Boolean
)

class GodheadApiException(
    val statusCode: Int,
    val serverMessage: String
) : IOException("GODHEAD API returned HTTP $statusCode: $serverMessage")

/**
 * HTTP client for the GODHEAD identity API.
 *
 * Pass an explicitly configured service URL. No production host or Telegram API
 * credentials are embedded in the application.
 */
class GodheadApiClient(baseUrl: String) {
    private val baseUrl: String
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        val parsed = baseUrl.trim().trimEnd('/').toHttpUrl()
        require(parsed.scheme == "https" || parsed.host == "10.0.2.2" ||
            parsed.host == "localhost" || parsed.host == "127.0.0.1") {
            "GODHEAD API must use HTTPS outside a local development environment"
        }
        this.baseUrl = parsed.toString().trimEnd('/') + "/"
    }

    suspend fun register(
        email: String,
        username: String,
        password: String,
        displayName: String
    ): GodheadAuthSession {
        require(password.length >= 12) { "Password must contain at least 12 characters" }
        val payload = JSONObject()
            .put("email", email.trim())
            .put("username", username.trim())
            .put("password", password)
            .put("display_name", displayName.trim())
        return authRequest("v1/auth/register", payload)
    }

    suspend fun login(email: String, password: String): GodheadAuthSession {
        val payload = JSONObject()
            .put("email", email.trim())
            .put("password", password)
        return authRequest("v1/auth/login", payload)
    }

    suspend fun logout(accessToken: String) {
        require(accessToken.isNotBlank()) { "Access token is required" }
        val request = Request.Builder()
            .url(baseUrl + "v1/auth/logout")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = execute(request)
        if (!response.optBoolean("revoked")) {
            throw IOException("GODHEAD did not confirm session revocation")
        }
    }

    suspend fun currentUser(accessToken: String): GodheadUser {
        require(accessToken.isNotBlank()) { "Access token is required" }
        val request = Request.Builder()
            .url(baseUrl + "v1/users/me")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return parseUser(execute(request))
    }

    suspend fun searchUsers(
        accessToken: String,
        query: String,
        limit: Int = 8
    ): List<GodheadPublicUser> {
        val normalized = query.trim().removePrefix("@")
        require(normalized.length in 2..64) { "Search must contain between 2 and 64 characters" }
        require(limit in 1..50) { "User search limit must be between 1 and 50" }
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/users/search")
            .addQueryParameter("query", normalized)
            .addQueryParameter("limit", limit.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val results = executeArray(request)
        return buildList(results.length()) {
            for (index in 0 until results.length()) {
                val user = results.getJSONObject(index)
                add(GodheadPublicUser(
                    id = user.getLong("id"),
                    username = user.getString("username"),
                    displayName = user.getString("display_name")
                ))
            }
        }
    }

    suspend fun openDirectConversation(accessToken: String, targetUserId: Long): GodheadConversation {
        require(targetUserId > 0) { "Target user ID must be positive" }
        val request = authorizedRequest("v1/conversations/direct/$targetUserId", accessToken)
            .post(JSONObject().toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return parseConversation(execute(request))
    }

    suspend fun createGroupConversation(
        accessToken: String,
        title: String,
        memberIds: List<Long>
    ): GodheadConversation {
        require(title.isNotBlank()) { "Group title cannot be blank" }
        val uniqueMembers = memberIds.filter { it > 0 }.distinct()
        require(uniqueMembers.size <= 199) { "A group can include at most 199 additional members" }
        val payload = JSONObject()
            .put("title", title.trim())
            .put("member_ids", JSONArray(uniqueMembers))
        val request = authorizedRequest("v1/conversations/groups", accessToken)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return parseConversation(execute(request))
    }

    suspend fun listConversations(
        accessToken: String,
        limit: Int = 50
    ): List<GodheadConversation> {
        require(limit in 1..100) { "Conversation limit must be between 1 and 100" }
        val request = authorizedRequest("v1/conversations?limit=$limit", accessToken)
            .get()
            .build()
        val array = executeArray(request)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                add(parseConversation(array.getJSONObject(index)))
            }
        }
    }

    suspend fun sendMessage(
        accessToken: String,
        conversationId: Long,
        content: String
    ): GodheadMessage {
        require(conversationId > 0) { "Conversation ID must be positive" }
        require(content.isNotBlank()) { "Message cannot be blank" }
        require(content.length <= 4096) { "Message cannot exceed 4096 characters" }
        val payload = JSONObject().put("content", content.trim())
        val request = authorizedRequest("v1/conversations/$conversationId/messages", accessToken)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return parseMessage(execute(request))
    }

    suspend fun uploadAttachment(
        accessToken: String,
        conversationId: Long,
        file: File,
        filename: String,
        contentType: String,
        caption: String = ""
    ): GodheadMessage {
        require(conversationId > 0) { "Conversation ID must be positive" }
        require(file.isFile && file.length() in 1..(25L * 1024 * 1024)) {
            "Attachment must be a file no larger than 25 MiB"
        }
        require(contentType.isNotBlank()) { "Attachment MIME type is required" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("caption", caption.take(4096))
            .addFormDataPart("file", filename, file.asRequestBody(contentType.toMediaType()))
            .build()
        val request = authorizedRequest(
            "v1/conversations/$conversationId/attachments", accessToken
        ).post(body).build()
        return parseMessage(execute(request))
    }

    suspend fun attachmentDownload(
        accessToken: String,
        conversationId: Long,
        attachmentId: Long
    ): GodheadAttachmentDownload {
        require(conversationId > 0 && attachmentId > 0) { "Attachment ID is invalid" }
        val request = authorizedRequest(
            "v1/conversations/$conversationId/attachments/$attachmentId/download", accessToken
        ).get().build()
        val response = execute(request)
        return GodheadAttachmentDownload(
            attachment = parseAttachment(response.getJSONObject("attachment")),
            url = response.getString("url"),
            expiresInSeconds = response.optLong("expires_in", 0)
        )
    }

    suspend fun downloadAttachment(
        accessToken: String,
        conversationId: Long,
        attachmentId: Long,
        output: OutputStream
    ): GodheadAttachment {
        val download = attachmentDownload(accessToken, conversationId, attachmentId)
        val localApiPath = download.url.startsWith("/")
        val url = if (localApiPath) baseUrl.trimEnd('/') + download.url else download.url
        val builder = Request.Builder().url(url).header("Accept", download.attachment.contentType).get()
        // Local disk downloads pass through the API ACL; S3 URLs are already
        // short-lived signed URLs and must not receive the GODHEAD bearer token.
        if (localApiPath) builder.header("Authorization", "Bearer $accessToken")
        withContext(Dispatchers.IO) {
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw GodheadApiException(response.code, "Attachment download failed")
                }
                val body = response.body ?: throw IOException("Attachment response was empty")
                if (body.contentLength() > 25L * 1024 * 1024) {
                    throw IOException("Attachment exceeds the 25 MiB limit")
                }
                body.byteStream().use { input -> input.copyTo(output) }
            }
        }
        return download.attachment
    }

    suspend fun acknowledgeDelivery(
        accessToken: String,
        conversationId: Long,
        messageId: Long
    ): GodheadDeliveryState {
        val request = authorizedRequest(
            "v1/conversations/$conversationId/messages/$messageId/delivery", accessToken
        ).post("{}".toRequestBody(JSON_MEDIA_TYPE)).build()
        return parseDeliveryState(execute(request))
    }

    suspend fun markConversationRead(
        accessToken: String,
        conversationId: Long,
        messageId: Long
    ): GodheadReadState {
        val payload = JSONObject().put("last_read_message_id", messageId)
            .toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = authorizedRequest(
            "v1/conversations/$conversationId/read", accessToken
        ).put(payload).build()
        return parseReadState(execute(request))
    }

    suspend fun listReadStates(
        accessToken: String,
        conversationId: Long
    ): List<GodheadReadState> {
        val request = authorizedRequest(
            "v1/conversations/$conversationId/read-states", accessToken
        ).get().build()
        val array = executeArray(request)
        return buildList(array.length()) {
            for (index in 0 until array.length()) add(parseReadState(array.getJSONObject(index)))
        }
    }

    suspend fun listDeliveryStates(
        accessToken: String,
        conversationId: Long,
        messageId: Long
    ): List<GodheadDeliveryState> {
        val request = authorizedRequest(
            "v1/conversations/$conversationId/messages/$messageId/delivery-states", accessToken
        ).get().build()
        val array = executeArray(request)
        return buildList(array.length()) {
            for (index in 0 until array.length()) add(parseDeliveryState(array.getJSONObject(index)))
        }
    }

    suspend fun listMessages(
        accessToken: String,
        conversationId: Long,
        beforeId: Long? = null,
        limit: Int = 50
    ): GodheadMessagePage {
        require(conversationId > 0) { "Conversation ID must be positive" }
        require(limit in 1..100) { "Message limit must be between 1 and 100" }
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/conversations/$conversationId/messages")
            .addQueryParameter("limit", limit.toString())
        if (beforeId != null) {
            require(beforeId > 0) { "Message cursor must be positive" }
            url.addQueryParameter("before_id", beforeId.toString())
        }
        val request = Request.Builder()
            .url(url.build())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val response = execute(request)
        val array = response.getJSONArray("items")
        val messages = buildList(array.length()) {
            for (index in 0 until array.length()) {
                add(parseMessage(array.getJSONObject(index)))
            }
        }
        val nextBefore = if (response.isNull("next_before_id")) null
            else response.getLong("next_before_id")
        return GodheadMessagePage(messages, nextBefore)
    }

    suspend fun listSessions(accessToken: String): List<GodheadRemoteSession> {
        val request = authorizedRequest("v1/auth/sessions", accessToken)
            .get()
            .build()
        val array = executeArray(request)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val session = array.getJSONObject(index)
                add(
                    GodheadRemoteSession(
                        id = session.getLong("id"),
                        createdAt = session.optString("created_at"),
                        expiresAt = session.optString("expires_at"),
                        isCurrent = session.optBoolean("is_current")
                    )
                )
            }
        }
    }

    suspend fun revokeSession(accessToken: String, sessionId: Long) {
        require(sessionId > 0) { "Session ID must be positive" }
        val request = authorizedRequest("v1/auth/sessions/$sessionId", accessToken)
            .delete()
            .build()
        val response = execute(request)
        if (!response.optBoolean("revoked")) {
            throw IOException("GODHEAD did not confirm remote session revocation")
        }
    }

    private fun authorizedRequest(path: String, token: String): Request.Builder {
        require(token.isNotBlank()) { "Access token is required" }
        return Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
    }

    private suspend fun authRequest(path: String, payload: JSONObject): GodheadAuthSession {
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = execute(request)
        return GodheadAuthSession(
            accessToken = response.getString("access_token"),
            tokenType = response.optString("token_type", "bearer"),
            expiresInSeconds = response.getLong("expires_in"),
            user = parseUser(response.getJSONObject("user"))
        )
    }

    private suspend fun execute(request: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = try {
                JSONObject(raw)
            } catch (_: Exception) {
                JSONObject()
            }
            if (!response.isSuccessful) {
                throw GodheadApiException(
                    response.code,
                    json.optString("detail").ifBlank { "Request failed" }
                )
            }
            json
        }
    }

    private suspend fun executeArray(request: Request): JSONArray = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try {
                    JSONObject(raw).optString("detail").ifBlank { "Request failed" }
                } catch (_: Exception) {
                    "Request failed"
                }
                throw GodheadApiException(response.code, detail)
            }
            JSONArray(raw)
        }
    }

    private fun parseMessage(json: JSONObject) = GodheadMessage(
        id = json.getLong("id"),
        conversationId = json.getLong("conversation_id"),
        senderId = json.getLong("sender_id"),
        content = json.getString("content"),
        createdAt = json.optString("created_at"),
        attachment = if (json.isNull("attachment")) null
            else parseAttachment(json.getJSONObject("attachment"))
    )

    private fun parseAttachment(json: JSONObject) = GodheadAttachment(
        id = json.getLong("id"),
        filename = json.getString("filename"),
        contentType = json.getString("content_type"),
        sizeBytes = json.getLong("size_bytes")
    )

    private fun parseReadState(json: JSONObject) = GodheadReadState(
        conversationId = json.getLong("conversation_id"),
        userId = json.getLong("user_id"),
        lastReadMessageId = if (json.isNull("last_read_message_id")) null
            else json.getLong("last_read_message_id")
    )

    private fun parseDeliveryState(json: JSONObject) = GodheadDeliveryState(
        messageId = json.getLong("message_id"),
        userId = json.getLong("user_id"),
        deliveredAt = if (json.isNull("delivered_at")) null else json.getString("delivered_at")
    )

    private fun parseConversation(json: JSONObject): GodheadConversation {
        val members = json.optJSONArray("member_ids") ?: JSONArray()
        val memberIds = buildList(members.length()) {
            for (index in 0 until members.length()) add(members.getLong(index))
        }
        val lastMessage = if (json.isNull("last_message")) null
            else parseMessage(json.getJSONObject("last_message"))
        return GodheadConversation(
            id = json.getLong("id"),
            kind = json.getString("kind"),
            title = if (json.isNull("title")) null else json.getString("title"),
            createdBy = json.getLong("created_by"),
            memberIds = memberIds,
            createdAt = json.optString("created_at"),
            updatedAt = json.optString("updated_at"),
            lastMessage = lastMessage,
            unreadCount = json.optInt("unread_count", 0)
        )
    }

    private fun parseUser(json: JSONObject) = GodheadUser(
        id = json.getLong("id"),
        email = json.getString("email"),
        username = json.getString("username"),
        displayName = json.getString("display_name"),
        createdAt = json.optString("created_at")
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
