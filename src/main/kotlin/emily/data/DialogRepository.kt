package emily.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DialogSummary(
    val id: String,
    val characterId: String,
    val characterName: String,
    val characterImageUrl: String,
    val storyId: String?,
    val storyTitle: String?,
    val lastMessage: String,
    val lastRole: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class DialogMessage(
    val role: String,
    val text: String,
    val createdAt: Long
)

class DialogRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val dialogsRef by lazy { database.getReference("dialogSessions") }
    private val messagesRef by lazy { database.getReference("dialogMessages") }

    suspend fun createDialog(
        userId: Long,
        characterId: String,
        characterName: String,
        characterImageUrl: String,
        storyId: String?,
        storyTitle: String?,
        initialMessage: String? = null,
        initialRole: String = "assistant"
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dialogId = dialogsRef.child(userId.toString()).push().key ?: "dialog_$now"
        val fallbackLastMessage = if (storyTitle.isNullOrBlank()) {
            "Свободный чат с $characterName"
        } else {
            storyTitle
        }
        val summary = mapOf(
            "id" to dialogId,
            "characterId" to characterId,
            "characterName" to characterName,
            "characterImageUrl" to characterImageUrl,
            "storyId" to storyId,
            "storyTitle" to storyTitle,
            "lastMessage" to (initialMessage?.takeIf { it.isNotBlank() } ?: fallbackLastMessage),
            "lastRole" to initialMessage?.takeIf { it.isNotBlank() }?.let { initialRole },
            "createdAt" to now,
            "updatedAt" to now
        )
        dialogsRef.child(userId.toString()).child(dialogId).setValueAsync(summary)

        if (!initialMessage.isNullOrBlank()) {
            appendMessageInternal(userId, dialogId, initialRole, initialMessage, now, updateSummary = false)
        }

        dialogId
    }

    suspend fun appendMessage(userId: Long, dialogId: String?, role: String, text: String): Any? = withContext(Dispatchers.IO) {
        val id = dialogId?.takeIf { it.isNotBlank() } ?: return@withContext null
        appendMessageInternal(userId, id, role, text, System.currentTimeMillis(), updateSummary = true)
    }

    suspend fun listDialogs(userId: Long, limit: Int = 30): List<DialogSummary> = withContext(Dispatchers.IO) {
        val snapshot = dialogsRef
            .child(userId.toString())
            .orderByChild("updatedAt")
            .limitToLast(limit)
            .awaitSingle()

        snapshot.children
            .mapNotNull { it.toDialogSummary() }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun getDialog(userId: Long, dialogId: String): DialogSummary? = withContext(Dispatchers.IO) {
        dialogsRef.child(userId.toString()).child(dialogId).awaitSingle().toDialogSummary()
    }

    suspend fun getMessages(userId: Long, dialogId: String, limit: Int = 80): List<DialogMessage> = withContext(Dispatchers.IO) {
        val snapshot = messagesRef
            .child(userId.toString())
            .child(dialogId)
            .orderByChild("createdAt")
            .limitToLast(limit)
            .awaitSingle()

        snapshot.children
            .mapNotNull { it.toDialogMessage() }
            .sortedBy { it.createdAt }
    }

    private fun appendMessageInternal(
        userId: Long,
        dialogId: String,
        role: String,
        text: String,
        createdAt: Long,
        updateSummary: Boolean
    ): Any? {
        val payload = mapOf(
            "role" to role,
            "text" to text,
            "createdAt" to createdAt
        )
        messagesRef.child(userId.toString()).child(dialogId).push().setValueAsync(payload)

        if (!updateSummary) return null

        val summaryUpdate = mapOf(
            "lastMessage" to text,
            "lastRole" to role,
            "updatedAt" to createdAt
        )
        return dialogsRef.child(userId.toString()).child(dialogId).updateChildrenAsync(summaryUpdate)
    }

    private fun DataSnapshot.toDialogSummary(): DialogSummary? {
        val id = child("id").getValue(String::class.java) ?: key ?: return null
        val characterId = child("characterId").getValue(String::class.java) ?: return null
        val characterName = child("characterName").getValue(String::class.java) ?: return null
        val characterImageUrl = child("characterImageUrl").getValue(String::class.java).orEmpty()
        val createdAt = child("createdAt").getValue(Long::class.java) ?: 0L
        val updatedAt = child("updatedAt").getValue(Long::class.java) ?: createdAt

        return DialogSummary(
            id = id,
            characterId = characterId,
            characterName = characterName,
            characterImageUrl = characterImageUrl,
            storyId = child("storyId").getValue(String::class.java),
            storyTitle = child("storyTitle").getValue(String::class.java),
            lastMessage = child("lastMessage").getValue(String::class.java).orEmpty(),
            lastRole = child("lastRole").getValue(String::class.java),
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun DataSnapshot.toDialogMessage(): DialogMessage? {
        val role = child("role").getValue(String::class.java) ?: return null
        val text = child("text").getValue(String::class.java) ?: return null
        val createdAt = child("createdAt").getValue(Long::class.java) ?: 0L
        return DialogMessage(role = role, text = text, createdAt = createdAt)
    }
}
