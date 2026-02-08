package emily.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UserActivity(
    val userId: Long,
    val chatId: Long,
    val freeMessagesWithoutSubscription: Int,
    val lastUsageAt: Long,
    val lastNudgeAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
)

class UserActivityRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val activityRef by lazy { database.getReference("userActivity") }

    suspend fun touch(userId: Long, chatId: Long, now: Long = System.currentTimeMillis()): Any? =
        withContext(Dispatchers.IO) {
            val current = activityRef.child(userId.toString()).awaitSingle()
            val createdAt = current.child("createdAt").getValue(Long::class.java) ?: now
            val freeMessages = current.child("freeMessagesWithoutSubscription").getValue(Long::class.java)?.toInt() ?: 0
            val lastNudgeAt = current.child("lastNudgeAt").getValue(Long::class.java)

            val payload = mapOf(
                "userId" to userId,
                "chatId" to chatId,
                "freeMessagesWithoutSubscription" to freeMessages,
                "lastUsageAt" to now,
                "lastNudgeAt" to lastNudgeAt,
                "createdAt" to createdAt,
                "updatedAt" to now
            )
            activityRef.child(userId.toString()).setValueAsync(payload)
        }

    suspend fun getOrCreate(userId: Long, chatId: Long): UserActivity = withContext(Dispatchers.IO) {
        val ref = activityRef.child(userId.toString())
        val snapshot = ref.awaitSingle()
        if (!snapshot.exists()) {
            val now = System.currentTimeMillis()
            val created = UserActivity(
                userId = userId,
                chatId = chatId,
                freeMessagesWithoutSubscription = 0,
                lastUsageAt = now,
                lastNudgeAt = null,
                createdAt = now,
                updatedAt = now
            )
            put(created)
            return@withContext created
        }
        snapshot.toUserActivity(userId, chatId)
    }

    suspend fun put(activity: UserActivity): Any? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "userId" to activity.userId,
            "chatId" to activity.chatId,
            "freeMessagesWithoutSubscription" to activity.freeMessagesWithoutSubscription,
            "lastUsageAt" to activity.lastUsageAt,
            "lastNudgeAt" to activity.lastNudgeAt,
            "createdAt" to activity.createdAt,
            "updatedAt" to now
        )
        activityRef.child(activity.userId.toString()).setValueAsync(payload)
    }

    suspend fun setFreeMessagesWithoutSubscription(userId: Long, value: Int): Any? = withContext(Dispatchers.IO) {
        activityRef.child(userId.toString()).child("freeMessagesWithoutSubscription").setValueAsync(value)
    }

    suspend fun markNudged(userId: Long, now: Long = System.currentTimeMillis()): Any? = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any>(
            "lastNudgeAt" to now,
            "updatedAt" to now
        )
        activityRef.child(userId.toString()).updateChildrenAsync(payload)
    }

    suspend fun listInactiveUsers(beforeTs: Long, limit: Int): List<UserActivity> = withContext(Dispatchers.IO) {
        val snapshot = activityRef
            .orderByChild("lastUsageAt")
            .endAt(beforeTs.toDouble())
            .limitToFirst(limit)
            .awaitSingle()

        if (!snapshot.exists()) {
            return@withContext emptyList<UserActivity>()
        }

        snapshot.children.mapNotNull { child ->
            val userId = child.key?.toLongOrNull() ?: return@mapNotNull null
            val chatId = child.child("chatId").getValue(Long::class.java) ?: userId
            child.toUserActivity(userId, chatId)
        }
    }

    private fun DataSnapshot.toUserActivity(userId: Long, fallbackChatId: Long): UserActivity {
        val now = System.currentTimeMillis()
        return UserActivity(
            userId = userId,
            chatId = child("chatId").getValue(Long::class.java) ?: fallbackChatId,
            freeMessagesWithoutSubscription = child("freeMessagesWithoutSubscription").getValue(Long::class.java)?.toInt() ?: 0,
            lastUsageAt = child("lastUsageAt").getValue(Long::class.java) ?: now,
            lastNudgeAt = child("lastNudgeAt").getValue(Long::class.java),
            createdAt = child("createdAt").getValue(Long::class.java) ?: now,
            updatedAt = child("updatedAt").getValue(Long::class.java) ?: now
        )
    }
}
