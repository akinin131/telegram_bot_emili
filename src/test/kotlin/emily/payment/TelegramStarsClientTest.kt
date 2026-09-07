package emily.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TelegramStarsClientTest {
    private val client = TelegramStarsClient("test-token")

    @Test
    fun `subscription invoice uses XTR and monthly period`() {
        val request = client.buildInvoiceRequest(
            title = "Pro",
            description = "Monthly plan",
            payload = "plan:pro:invoice-id",
            priceLabel = "Pro",
            priceStars = 999,
            photoUrl = null,
            subscription = true
        )

        assertEquals("XTR", request.getString("currency"))
        assertEquals("", request.getString("provider_token"))
        assertEquals(TELEGRAM_MONTH_SECONDS, request.getInt("subscription_period"))
        assertEquals(999, request.getJSONArray("prices").getJSONObject(0).getInt("amount"))
        assertFalse(request.has("need_email"))
        assertFalse(request.has("provider_data"))
    }

    @Test
    fun `one time Stars invoice has no subscription period`() {
        val request = client.buildInvoiceRequest(
            title = "Photos",
            description = "Photo pack",
            payload = "pack:pack10:invoice-id",
            priceLabel = "Photos",
            priceStars = 99,
            photoUrl = null,
            subscription = false
        )

        assertEquals("XTR", request.getString("currency"))
        assertFalse(request.has("subscription_period"))
    }
}
