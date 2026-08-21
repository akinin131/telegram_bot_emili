package emily.data

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PromoRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val promoRef by lazy { database.getReference("promoRedemptions") }

    suspend fun redeem(userId: Long, promoCode: String, payload: Map<String, Any?> = emptyMap()): Boolean = withContext(Dispatchers.IO) {
        val normalizedCode = promoCode.trim().uppercase()
        val redemptionRef = promoRef.child(normalizedCode).child(userId.toString())
        if (redemptionRef.awaitSingle().exists()) {
            return@withContext false
        }

        redemptionRef.setValueAsync(
            mutableMapOf<String, Any?>(
                "userId" to userId,
                "promoCode" to normalizedCode,
                "createdAt" to System.currentTimeMillis()
            ).apply { putAll(payload) }
        )
        true
    }

    suspend fun hasRedemption(userId: Long, promoCode: String): Boolean = withContext(Dispatchers.IO) {
        val normalizedCode = promoCode.trim().uppercase()
        promoRef.child(normalizedCode).child(userId.toString()).awaitSingle().exists()
    }
}
