package emily.data

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AdminAccess(
    val userId: Long,
    val grantedAt: Long,
    val updatedAt: Long
)

class AdminRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val accessRef by lazy { database.getReference("adminAccess") }

    suspend fun hasAccess(userId: Long): Boolean = withContext(Dispatchers.IO) {
        accessRef.child(userId.toString()).child("enabled").awaitSingle()
            .getValue(Boolean::class.java) == true
    }

    suspend fun grantAccess(userId: Long): AdminAccess = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val ref = accessRef.child(userId.toString())
        val current = ref.awaitSingle()
        val grantedAt = current.child("grantedAt").getValue(Long::class.java) ?: now
        val access = AdminAccess(
            userId = userId,
            grantedAt = grantedAt,
            updatedAt = now
        )
        ref.setValueAsync(
            mapOf(
                "userId" to userId,
                "enabled" to true,
                "grantedAt" to grantedAt,
                "updatedAt" to now
            )
        )
        access
    }
}
