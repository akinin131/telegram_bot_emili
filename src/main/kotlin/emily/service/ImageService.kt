package emily.service

import emily.http.await
import java.util.Base64
import java.util.concurrent.ThreadLocalRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ImageService(
    private val client: OkHttpClient,
    private val apiToken: String,
    private val model: String
) {
    private val json = "application/json".toMediaType()

    suspend fun generateImage(prompt: String, persona: String): ByteArray? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("prompt", "$prompt, $persona")
            .put("seed", ThreadLocalRandom.current().nextInt(0, 1_000_000_000))
            .put("width", 960)
            .put("height", 1280)
            .put("steps", 30)
            .put("format", "png")
            .put("safe_mode", false)
            .toString()

        val request = Request.Builder()
            .url("https://api.venice.ai/api/v1/image/generate")
            .header("Authorization", "Bearer $apiToken")
            .header("Accept", "application/json")
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody(json))
            .build()

        val response = client.newCall(request).await()
        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return@withContext null
            val jsonBody = JSONObject(raw)
            jsonBody.optJSONArray("images")?.let { arr ->
                decode(arr.optString(0))?.let { return@withContext it }
            }
            jsonBody.optJSONArray("data")?.let { arr ->
                decode(arr.optJSONObject(0)?.optString("b64_json"))?.let { return@withContext it }
            }
            decode(jsonBody.optString("image"))
        }
    }

    private fun decode(b64: String?): ByteArray? {
        if (b64.isNullOrBlank()) return null
        val clean = b64.replace("\\s".toRegex(), "")
        return runCatching { Base64.getDecoder().decode(clean) }.getOrNull()
    }
}
