package emily.data

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnalyticsRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val rootRef by lazy { database.getReference("analytics") }

    suspend fun logSpend(
        userId: Long,
        plan: String?,
        spentTextTokens: Int,
        spentImageCredits: Int,
        textAvailableBefore: Int,
        imageAvailableBefore: Int,
        textLeftAfter: Int,
        imageLeftAfter: Int,
        source: String
    ): Any? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val day = LocalDate.now().toString()
        val tariffType = tariffType(plan)
        val userPath = "daily/$day/users/$userId"
        upsertCounters(userPath) { node ->
            node["userId"] = userId
            node["plan"] = plan ?: "free"
            node["tariffType"] = tariffType
            node["updatedAt"] = now
            node["lastSpendSource"] = source
            node["spendEvents"] = number(node["spendEvents"]) + 1L
            node["spentTextTokens"] = number(node["spentTextTokens"]) + spentTextTokens.toLong()
            node["spentImageCredits"] = number(node["spentImageCredits"]) + spentImageCredits.toLong()
            node["textAvailableBeforeLast"] = textAvailableBefore
            node["imageAvailableBeforeLast"] = imageAvailableBefore
            node["textLeftAfterLast"] = textLeftAfter
            node["imageLeftAfterLast"] = imageLeftAfter
        }
    }

    suspend fun logTopUp(
        userId: Long,
        plan: String?,
        topupTextTokens: Int,
        topupImageCredits: Int,
        source: String,
        amountRub: Int? = null
    ): Any? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val day = LocalDate.now().toString()
        val tariffType = tariffType(plan)
        val userPath = "daily/$day/users/$userId"
        upsertCounters(userPath) { node ->
            node["userId"] = userId
            node["plan"] = plan ?: "free"
            node["tariffType"] = tariffType
            node["updatedAt"] = now
            node["lastTopupSource"] = source
            node["lastTopupRub"] = amountRub
            node["topupEvents"] = number(node["topupEvents"]) + 1L
            node["topupTextTokens"] = number(node["topupTextTokens"]) + topupTextTokens.toLong()
            node["topupImageCredits"] = number(node["topupImageCredits"]) + topupImageCredits.toLong()
        }
    }

    private fun tariffType(plan: String?): String = if (plan == null) "free" else "paid"

    private fun upsertCounters(path: String, update: (MutableMap<String, Any?>) -> Unit) {
        rootRef.child(path).runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val existing = (currentData.value as? Map<*, *>)
                    ?.entries
                    ?.associate { it.key.toString() to it.value }
                    ?.toMutableMap()
                    ?: mutableMapOf()
                update(existing)
                currentData.value = existing
                return Transaction.success(currentData)
            }

            override fun onComplete(
                error: com.google.firebase.database.DatabaseError?,
                committed: Boolean,
                currentData: com.google.firebase.database.DataSnapshot?
            ) {
            }
        })
    }

    private fun number(value: Any?): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Double -> value.toLong()
        else -> 0L
    }
}
