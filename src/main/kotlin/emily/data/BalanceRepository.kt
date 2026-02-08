package emily.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BalanceRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance(),
    private val enableDetailedUsageLog: Boolean = false
) {
    private val balancesRef by lazy { database.getReference("balances") }
    private val paymentsRef by lazy { database.getReference("payments") }

    suspend fun get(userId: Long): UserBalance = withContext(Dispatchers.IO) {
        val ref = balancesRef.child(userId.toString())
        val snapshot = ref.awaitSingle()
        if (snapshot.exists()) snapshot.toBalance(userId) else createDefault(userId)
    }

    suspend fun put(balance: UserBalance): Any? = withContext(Dispatchers.IO) {
        balance.updatedAt = System.currentTimeMillis()
        val payload = mapOf(
            "userId" to balance.userId,
            "plan" to balance.plan,
            "planExpiresAt" to balance.planExpiresAt,
            "textTokensLeft" to balance.textTokensLeft,
            "imageCreditsLeft" to balance.imageCreditsLeft,
            "dayImageUsed" to balance.dayImageUsed,
            "dayStamp" to balance.dayStamp,
            "createdAt" to balance.createdAt,
            "updatedAt" to balance.updatedAt
        )
        balancesRef.child(balance.userId.toString()).setValueAsync(payload)
    }

    suspend fun addPayment(userId: Long, payload: String, amountRub: Int): Any? = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val payloadMap = mapOf(
            "payload" to payload,
            "amountRub" to amountRub,
            "ts" to System.currentTimeMillis()
        )
        paymentsRef.child(userId.toString()).child(id).setValueAsync(payloadMap)
    }

    suspend fun logUsage(userId: Long, tokens: Int, meta: Map<String, Any?> = emptyMap()): Any? = withContext(Dispatchers.IO) {
        if (!enableDetailedUsageLog) return@withContext null
        val id = UUID.randomUUID().toString()
        val payload = mutableMapOf<String, Any?>(
            "tokens" to tokens,
            "ts" to System.currentTimeMillis()
        ).apply { putAll(meta) }
        paymentsRef.child(userId.toString()).child("usage").child(id).setValueAsync(payload)
    }

    private suspend fun createDefault(userId: Long): UserBalance {
        val fresh = UserBalance(userId = userId)
        put(fresh)
        return fresh
    }

    private fun DataSnapshot.toBalance(userId: Long): UserBalance = UserBalance(
        userId = userId,
        plan = child("plan").getValue(String::class.java),
        planExpiresAt = child("planExpiresAt").getValue(Long::class.java),
        textTokensLeft = child("textTokensLeft").getValue(Long::class.java)?.toInt() ?: FREE_TEXT_TOKENS,
        imageCreditsLeft = child("imageCreditsLeft").getValue(Long::class.java)?.toInt() ?: FREE_IMAGE_CREDITS,
        dayImageUsed = child("dayImageUsed").getValue(Long::class.java)?.toInt() ?: 0,
        dayStamp = child("dayStamp").getValue(String::class.java) ?: LocalDate.now().toString(),
        createdAt = child("createdAt").getValue(Long::class.java) ?: System.currentTimeMillis(),
        updatedAt = child("updatedAt").getValue(Long::class.java) ?: System.currentTimeMillis()
    )

}
