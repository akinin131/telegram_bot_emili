package emily.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.slf4j.LoggerFactory

class MyMemoryTranslator(
    private val client: OkHttpClient
) {

    private val log = LoggerFactory.getLogger(MyMemoryTranslator::class.java)
    private val endpoint = "api.mymemory.translated.net"

    suspend fun translate(text: String, sourceLang: String, targetLang: String): String? =
        withContext(Dispatchers.IO) {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host(endpoint)
                .addPathSegment("get")
                .addQueryParameter("q", text)
                .addQueryParameter("langpair", "$sourceLang|$targetLang")
                .build()

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.warn("MyMemory translate failed: {}", response.code)
                        return@withContext null
                    }
                    val body = response.body?.string()
                    val json = body?.let { JSONObject(it) }
                    val translated = json
                        ?.optJSONObject("responseData")
                        ?.optString("translatedText")
                    log.info(
                        "MyMemory translated '{}' -> '{}'",
                        preview(text),
                        preview(translated)
                    )
                    return@withContext translated
                }
            }.getOrElse {
                log.error("MyMemory translate error", it)
                null
            }
        }

    private fun preview(text: String?, max: Int = 40): String {
        if (text.isNullOrBlank()) return ""
        return if (text.length <= max) text else text.take(max) + "…"
    }
}
