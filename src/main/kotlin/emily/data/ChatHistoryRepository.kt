package emily.data

import com.google.firebase.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ChatTurn(
    val role: String,
    val text: String,
    val createdAt: Long
)

class ChatHistoryRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {

    private val chatsRef by lazy { database.getReference("chatHistory") }

    suspend fun append(userId: Long, role: String, text: String): Any? = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "role" to role,
            "text" to text,
            "createdAt" to System.currentTimeMillis()
        )
        chatsRef.child(userId.toString()).push().setValueAsync(payload)
    }

    suspend fun getLast(userId: Long, limit: Int): List<ChatTurn> = withContext(Dispatchers.IO) {
        val query = chatsRef
            .child(userId.toString())
            .orderByChild("createdAt")
            .limitToLast(limit)

        suspendCancellableCoroutine { cont ->
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!cont.isActive) return

                    if (!snapshot.exists()) {
                        cont.resume(emptyList())
                        return
                    }

                    val turns = snapshot.children.mapNotNull { child ->
                        val role = child.child("role").getValue(String::class.java)
                        val text = child.child("text").getValue(String::class.java)
                        val createdAt = child.child("createdAt").getValue(Long::class.java) ?: 0L

                        if (role == null || text == null) {
                            null
                        } else {
                            ChatTurn(
                                role = role,
                                text = text,
                                createdAt = createdAt
                            )
                        }
                    }.sortedBy { it.createdAt }

                    cont.resume(turns)
                }

                override fun onCancelled(error: DatabaseError) {
                    if (cont.isActive) {
                        cont.resumeWithException(error.toException())
                    }
                }
            }

            query.addListenerForSingleValueEvent(listener)

            cont.invokeOnCancellation {
                try {
                    query.removeEventListener(listener)
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun clear(userId: Long): Any? = withContext(Dispatchers.IO) {
        chatsRef.child(userId.toString()).setValueAsync(null)
    }
}
