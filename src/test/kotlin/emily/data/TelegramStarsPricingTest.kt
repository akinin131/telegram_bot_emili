package emily.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramStarsPricingTest {
    @Test
    fun `Stars prices preserve target ruble revenue`() {
        val targets = listOf(99, 149, 150, 269, 349, 399, 619, 999, 1309, 1890)

        targets.forEach { targetRub ->
            val stars = TelegramStarsPricing.forTargetRub(targetRub)
            val payoutRub = stars * TelegramStarsPricing.rewardRubPerStar
            assertTrue(payoutRub >= targetRub)
            assertTrue((stars - 1) * TelegramStarsPricing.rewardRubPerStar < targetRub)
        }
    }

    @Test
    fun `catalog exposes one unlimited monthly subscription`() {
        assertEquals(1, Plan.entries.size)
        assertEquals(320, Plan.BASIC.priceStars)
        assertTrue(Plan.BASIC.unlimitedText)
        assertEquals(0, Plan.BASIC.monthlyTextTokens)
        assertEquals(100, Plan.BASIC.monthlyImageCredits)
        assertEquals(320, SubscriptionPricing.effectivePriceStars(Plan.BASIC, hasSub5Promo = false))
        assertEquals(5, SubscriptionPricing.effectivePriceStars(Plan.BASIC, hasSub5Promo = true))
        assertEquals(90, ImagePack.P10.priceStars)
        assertEquals(136, CustomStoryPack.priceStars)
    }
}
