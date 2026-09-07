package emily.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RecurringSubscriptionStatus {
    ACTIVE,
    PAST_DUE,
    CANCEL_AT_PERIOD_END,
    CANCELED
}

data class RecurringSubscription(
    val userId: Long,
    val planCode: String,
    val status: RecurringSubscriptionStatus,
    val invoicePayload: String,
    val telegramPaymentChargeId: String,
    val lastTelegramPaymentChargeId: String,
    val currentPeriodEnd: Long,
    val canceledAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun effectiveStatus(now: Long = System.currentTimeMillis()): RecurringSubscriptionStatus =
        if (currentPeriodEnd <= now) {
            RecurringSubscriptionStatus.CANCELED
        } else {
            status
        }

    fun isCurrent(now: Long = System.currentTimeMillis()): Boolean = currentPeriodEnd > now
}

class SubscriptionRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val subscriptionsRef by lazy { database.getReference("recurringSubscriptions") }

    suspend fun get(userId: Long): RecurringSubscription? = withContext(Dispatchers.IO) {
        val snapshot = subscriptionsRef.child(userId.toString()).awaitSingle()
        if (snapshot.exists()) snapshot.toSubscription(userId) else null
    }

    suspend fun put(subscription: RecurringSubscription): Any? = withContext(Dispatchers.IO) {
        subscriptionsRef.child(subscription.userId.toString()).setValueAsync(subscription.toPayload())
    }

    private fun RecurringSubscription.toPayload(): Map<String, Any?> = mapOf(
        "userId" to userId,
        "provider" to "telegram_stars",
        "planCode" to planCode,
        "status" to status.name,
        "invoicePayload" to invoicePayload,
        "telegramPaymentChargeId" to telegramPaymentChargeId,
        "lastTelegramPaymentChargeId" to lastTelegramPaymentChargeId,
        "currentPeriodEnd" to currentPeriodEnd,
        "canceledAt" to canceledAt,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    private fun DataSnapshot.toSubscription(userId: Long): RecurringSubscription? {
        val planCode = child("planCode").getValue(String::class.java) ?: return null
        val initialChargeId = child("telegramPaymentChargeId").getValue(String::class.java)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val currentPeriodEnd = child("currentPeriodEnd").longValue() ?: return null
        val now = System.currentTimeMillis()
        val storedStatus = runCatching {
            RecurringSubscriptionStatus.valueOf(
                child("status").getValue(String::class.java) ?: RecurringSubscriptionStatus.CANCELED.name
            )
        }.getOrDefault(RecurringSubscriptionStatus.CANCELED)
        val status = if (currentPeriodEnd <= now) {
            RecurringSubscriptionStatus.CANCELED
        } else {
            storedStatus
        }
        return RecurringSubscription(
            userId = userId,
            planCode = planCode,
            status = status,
            invoicePayload = child("invoicePayload").getValue(String::class.java).orEmpty(),
            telegramPaymentChargeId = initialChargeId,
            lastTelegramPaymentChargeId = child("lastTelegramPaymentChargeId").getValue(String::class.java)
                ?.takeIf { it.isNotBlank() }
                ?: initialChargeId,
            currentPeriodEnd = currentPeriodEnd,
            canceledAt = child("canceledAt").longValue(),
            createdAt = child("createdAt").longValue() ?: now,
            updatedAt = child("updatedAt").longValue() ?: now
        )
    }

    private fun DataSnapshot.longValue(): Long? = getValue(Long::class.java)
}
