package org.telegram.messenger.godhead

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
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

    suspend fun currentUser(accessToken: String): GodheadUser {
        require(accessToken.isNotBlank()) { "Access token is required" }
        val request = Request.Builder()
            .url(baseUrl + "v1/users/me")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return parseUser(execute(request).getJSONObject("user"))
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
