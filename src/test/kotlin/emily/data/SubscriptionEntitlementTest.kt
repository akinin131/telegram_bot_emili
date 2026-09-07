package emily.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionEntitlementTest {
    @Test
    fun `active plan gives unlimited text without replacing saved tokens`() {
        val now = 1_000L
        val balance = UserBalance(
            userId = 42L,
            plan = Plan.BASIC.code,
            planExpiresAt = now + 10_000L,
            textTokensLeft = 777
        )

        assertTrue(balance.hasUnlimitedText(now))
        assertEquals(777, balance.textTokensLeft)
    }

    @Test
    fun `unlimited text ends with subscription and saved tokens remain`() {
        val now = 20_000L
        val balance = UserBalance(
            userId = 42L,
            plan = Plan.BASIC.code,
            planExpiresAt = now,
            textTokensLeft = 777
        )

        assertFalse(balance.hasUnlimitedText(now))
        assertEquals(777, balance.textTokensLeft)
    }

    @Test
    fun `legacy active plan also keeps unlimited text until its expiry`() {
        val balance = UserBalance(
            userId = 42L,
            plan = "pro",
            planExpiresAt = 2_000L,
            textTokensLeft = 123
        )

        assertTrue(balance.hasUnlimitedText(1_000L))
        assertEquals(123, balance.textTokensLeft)
    }

    @Test
    fun `expired recurring subscription is canceled locally`() {
        val subscription = RecurringSubscription(
            userId = 42L,
            planCode = Plan.BASIC.code,
            status = RecurringSubscriptionStatus.ACTIVE,
            invoicePayload = "plan:basic:test",
            telegramPaymentChargeId = "first-charge",
            lastTelegramPaymentChargeId = "last-charge",
            currentPeriodEnd = 10_000L,
            createdAt = 1_000L,
            updatedAt = 1_000L
        )

        assertEquals(RecurringSubscriptionStatus.CANCELED, subscription.effectiveStatus(10_000L))
    }
}
