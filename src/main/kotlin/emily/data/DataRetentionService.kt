package emily.data

import com.google.firebase.database.FirebaseDatabase
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RetentionCleanupResult(
    val deletedUsageEvents: Int,
    val deletedAnalyticsDays: Int,
    val deletedAnalyticsEventDays: Int
)

class DataRetentionService(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val paymentsRef by lazy { database.getReference("payments") }
    private val analyticsRef by lazy { database.getReference("analytics") }

    suspend fun cleanupOlderThanDays(days: Int = 60): RetentionCleanupResult = withContext(Dispatchers.IO) {
        val safeDays = days.coerceAtLeast(1)
        val cutoffMs = System.currentTimeMillis() - safeDays * 24L * 60L * 60L * 1000L
        val cutoffDate = LocalDate.now().minusDays(safeDays.toLong())

        var deletedUsageEvents = 0
        var deletedAnalyticsDays = 0
        var deletedAnalyticsEventDays = 0

        val paymentsSnapshot = paymentsRef.awaitSingle(timeoutMs = 30_000L)
        paymentsSnapshot.children.forEach { userNode ->
            val usageNode = userNode.child("usage")
            usageNode.children.forEach { usageEvent ->
                val ts = usageEvent.child("ts").getValue(Long::class.java) ?: 0L
                if (ts in 1 until cutoffMs) {
                    paymentsRef
                        .child(userNode.key.orEmpty())
                        .child("usage")
                        .child(usageEvent.key.orEmpty())
                        .setValueAsync(null)
                    deletedUsageEvents += 1
                }
            }
        }

        val dailySnapshot = analyticsRef.child("daily").awaitSingle(timeoutMs = 30_000L)
        dailySnapshot.children.forEach { dayNode ->
            val dayKey = dayNode.key ?: return@forEach
            val day = runCatching { LocalDate.parse(dayKey) }.getOrNull() ?: return@forEach
            if (day.isBefore(cutoffDate)) {
                analyticsRef.child("daily").child(dayKey).setValueAsync(null)
                deletedAnalyticsDays += 1
            }
        }

        val eventsSnapshot = analyticsRef.child("events").awaitSingle(timeoutMs = 30_000L)
        eventsSnapshot.children.forEach { dayNode ->
            val dayKey = dayNode.key ?: return@forEach
            val day = runCatching { LocalDate.parse(dayKey) }.getOrNull() ?: return@forEach
            if (day.isBefore(cutoffDate)) {
                analyticsRef.child("events").child(dayKey).setValueAsync(null)
                deletedAnalyticsEventDays += 1
            }
        }

        RetentionCleanupResult(
            deletedUsageEvents = deletedUsageEvents,
            deletedAnalyticsDays = deletedAnalyticsDays,
            deletedAnalyticsEventDays = deletedAnalyticsEventDays
        )
    }
}
