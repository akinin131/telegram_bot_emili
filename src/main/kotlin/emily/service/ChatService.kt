package emily.service

import emily.http.await
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ChatService(
    private val client: OkHttpClient,
    private val apiToken: String,
    private val model: String
) {
    private val json = "application/json".toMediaType()

    data class ChatResult(val text: String, val tokensUsed: Int, val rawUsage: JSONObject? = null)

    suspend fun generateReply(history: List<Pair<String, String>>): ChatResult = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            history.forEach { (role, content) ->
                put(JSONObject().put("role", role).put("content", content))
            }
        }
        val bodyStr = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .toString()

        val request = Request.Builder()
            .url("https://api.venice.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiToken")
            .header("Accept", "application/json")
            .post(bodyStr.toByteArray(Charsets.UTF_8).toRequestBody(json))
            .build()

        val response = client.newCall(request).await()
        response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return@withContext ChatResult(
                    text = "Проблемы со связью 😢 Попробуем ещё раз?",
                    tokensUsed = 0,
                    rawUsage = null
                )
            }
            val jsonBody = JSONObject(body)
            val content = jsonBody.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.ifBlank { "..." }
                ?: "..."
            var tokens = 0
            var usageJson: JSONObject? = null
            jsonBody.optJSONObject("usage")?.let { usage ->
                usageJson = usage
                tokens = usage.optInt("total_tokens", -1)
                if (tokens < 0) {
                    tokens = usage.optInt("prompt_tokens", 0) + usage.optInt("completion_tokens", 0)
                }
            }
            if (tokens <= 0) {
                val lastUserMsg = history.lastOrNull { it.first == "user" }?.second ?: ""
                tokens = max(1, ceil(lastUserMsg.length / 4.0).toInt())
            }
            return@withContext ChatResult(content, tokens, usageJson)
        }
    }
}
