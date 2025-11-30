package emily.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.slf4j.LoggerFactory

class YandexTranslator(
    private val apiKey: String,
    private val folderId: String,
    private val client: OkHttpClient
) {

    private val log = LoggerFactory.getLogger(YandexTranslator::class.java)
    private val mediaType = "application/json".toMediaType()
    private val endpoint = "https://translate.api.cloud.yandex.net/translate/v2/translate"

    suspend fun translate(text: String, sourceLang: String, targetLang: String): String? =
        withContext(Dispatchers.IO) {
            val bodyJson = JSONObject().apply {
                put("folderId", folderId)
                put("texts", listOf(text))
                put("sourceLanguageCode", sourceLang)
                put("targetLanguageCode", targetLang)
            }

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Api-Key $apiKey")
                .post(bodyJson.toString().toRequestBody(mediaType))
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.warn("Yandex translate failed: {}", response.code)
                        return@withContext null
                    }
                    val body = response.body?.string()
                    val json = body?.let { JSONObject(it) }
                    val translations = json?.optJSONArray("translations")
                    val first = translations?.optJSONObject(0)
                    val translated = first?.optString("text")
                    log.info("Yandex translated '{}' -> '{}'", preview(text), preview(translated))
                    return@withContext translated
                }
            }.getOrElse {
                log.error("Yandex translate error", it)
                null
            }
        }

    private fun preview(text: String?, max: Int = 40): String {
        if (text.isNullOrBlank()) return ""
        return if (text.length <= max) text else text.take(max) + "…"
    }
}
