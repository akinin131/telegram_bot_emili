package emily.payment

import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

const val TELEGRAM_STARS_CURRENCY = "XTR"
const val TELEGRAM_MONTH_SECONDS = 30 * 24 * 60 * 60

data class TelegramStarsResult(
    val ok: Boolean,
    val statusCode: Int,
    val description: String,
    val messageId: Int? = null,
    val invoiceLink: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("statusCode", statusCode)
        .put("description", description)
}

class TelegramStarsClient(
    private val botToken: String,
    private val apiBaseUrl: String = "https://api.telegram.org"
) {
    fun sendInvoice(
        chatId: Long,
        title: String,
        description: String,
        payload: String,
        priceLabel: String,
        priceStars: Int,
        photoUrl: String? = null,
        subscription: Boolean = false
    ): TelegramStarsResult {
        val request = buildInvoiceRequest(
            title = title,
            description = description,
            payload = payload,
            priceLabel = priceLabel,
            priceStars = priceStars,
            photoUrl = photoUrl,
            subscription = subscription
        ).put("chat_id", chatId)
        return post("sendInvoice", request)
    }

    fun createInvoiceLink(
        title: String,
        description: String,
        payload: String,
        priceLabel: String,
        priceStars: Int,
        photoUrl: String? = null,
        subscription: Boolean = false
    ): TelegramStarsResult = post(
        "createInvoiceLink",
        buildInvoiceRequest(
            title = title,
            description = description,
            payload = payload,
            priceLabel = priceLabel,
            priceStars = priceStars,
            photoUrl = photoUrl,
            subscription = subscription
        )
    )

    fun cancelSubscription(userId: Long, telegramPaymentChargeId: String): TelegramStarsResult = post(
        "editUserStarSubscription",
        JSONObject()
            .put("user_id", userId)
            .put("telegram_payment_charge_id", telegramPaymentChargeId)
            .put("is_canceled", true)
    )

    internal fun buildInvoiceRequest(
        title: String,
        description: String,
        payload: String,
        priceLabel: String,
        priceStars: Int,
        photoUrl: String?,
        subscription: Boolean
    ): JSONObject {
        require(priceStars in 1..10_000) { "Telegram Stars price must be between 1 and 10000" }
        val request = JSONObject()
            .put("title", title)
            .put("description", description)
            .put("payload", payload)
            .put("provider_token", "")
            .put("currency", TELEGRAM_STARS_CURRENCY)
            .put("prices", JSONArray().put(JSONObject()
                .put("label", priceLabel)
                .put("amount", priceStars)
            ))
        if (subscription) {
            request.put("subscription_period", TELEGRAM_MONTH_SECONDS)
        }
        if (!photoUrl.isNullOrBlank()) {
            request
                .put("photo_url", photoUrl)
                .put("photo_width", 960)
                .put("photo_height", 1280)
        }
        return request
    }

    private fun post(method: String, request: JSONObject): TelegramStarsResult = runCatching {
        val body = request.toString().toByteArray(StandardCharsets.UTF_8)
        val connection = URI.create("${apiBaseUrl.trimEnd('/')}/bot$botToken/$method")
            .toURL()
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            val json = runCatching { JSONObject(response) }.getOrNull()
            val ok = status in 200..299 && json?.optBoolean("ok", false) == true
            val result = json?.opt("result")
            TelegramStarsResult(
                ok = ok,
                statusCode = status,
                description = json?.optString("description")?.takeIf { it.isNotBlank() }
                    ?: if (ok) "Telegram accepted request" else response.ifBlank { "Empty Telegram response" },
                messageId = (result as? JSONObject)?.optInt("message_id")?.takeIf { it > 0 },
                invoiceLink = (result as? String)?.takeIf { it.startsWith("http") }
            )
        } finally {
            connection.disconnect()
        }
    }.getOrElse { error ->
        TelegramStarsResult(
            ok = false,
            statusCode = 0,
            description = error.message ?: "Telegram request failed"
        )
    }
}
