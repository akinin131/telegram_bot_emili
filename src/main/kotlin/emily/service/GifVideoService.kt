package emily.service

import emily.http.await
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class GifVideoService(
    private val client: OkHttpClient,
    private val apiToken: String,
    private val model: String
) {
    private val json = "application/json".toMediaType()

    suspend fun animateImage(
        imageUrl: String,
        prompt: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        val queueBody = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("duration", "5s")
            .put("image_url", imageUrl)
            .toString()

        val queueRequest = Request.Builder()
            .url("https://api.venice.ai/api/v1/video/queue")
            .header("Authorization", "Bearer $apiToken")
            .header("Accept", "application/json")
            .post(queueBody.toByteArray(Charsets.UTF_8).toRequestBody(json))
            .build()

        client.newCall(queueRequest).await().use { queueResp ->
            if (!queueResp.isSuccessful) return@withContext null

            val queueJson = JSONObject(queueResp.body?.string().orEmpty())
            val responseModel = queueJson.optString("model").ifBlank { model }
            val queueId = queueJson.optString("queue_id").takeIf { it.isNotBlank() } ?: return@withContext null

            repeat(48) { attempt ->
                delay(if (attempt == 0) 3000L else 5000L)

                val retrieveBody = JSONObject()
                    .put("model", responseModel)
                    .put("queue_id", queueId)
                    .put("delete_media_on_completion", true)
                    .toString()

                val retrieveRequest = Request.Builder()
                    .url("https://api.venice.ai/api/v1/video/retrieve")
                    .header("Authorization", "Bearer $apiToken")
                    .header("Accept", "video/mp4, application/json")
                    .post(retrieveBody.toByteArray(Charsets.UTF_8).toRequestBody(json))
                    .build()

                client.newCall(retrieveRequest).await().use { retrieveResp ->
                    if (!retrieveResp.isSuccessful) return@withContext null

                    val contentType = retrieveResp.header("Content-Type").orEmpty()
                    if (contentType.contains("video/mp4", ignoreCase = true)) {
                        return@withContext retrieveResp.body?.bytes()
                    }

                    val statusJson = JSONObject(retrieveResp.body?.string().orEmpty())
                    val status = statusJson.optString("status")
                    if (status.equals("FAILED", ignoreCase = true) || status.equals("ERROR", ignoreCase = true)) {
                        return@withContext null
                    }

                    val averageExecutionTime = statusJson.optLong("average_execution_time", 0L)
                    if (averageExecutionTime > 0L) {
                        delay(max(0L, averageExecutionTime / 8))
                    }
                }
            }

            null
        }
    }
}
