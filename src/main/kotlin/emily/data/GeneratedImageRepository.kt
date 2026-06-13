package emily.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GeneratedImageItem(
    val id: String,
    val characterId: String,
    val characterName: String,
    val telegramFileId: String,
    val prompt: String,
    val model: String,
    val source: String,
    val createdAt: Long
)

class GeneratedImageRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val imagesRef by lazy { database.getReference("generatedImages") }

    suspend fun add(
        userId: Long,
        characterId: String,
        characterName: String,
        telegramFileId: String,
        prompt: String,
        model: String,
        source: String
    ): GeneratedImageItem = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val ref = imagesRef.child(userId.toString()).child(characterId).push()
        val id = ref.key ?: "image_$now"
        val item = GeneratedImageItem(
            id = id,
            characterId = characterId,
            characterName = characterName,
            telegramFileId = telegramFileId,
            prompt = prompt,
            model = model,
            source = source,
            createdAt = now
        )
        ref.setValueAsync(item.toPayload())
        item
    }

    suspend fun list(userId: Long, characterId: String, limit: Int = 80): List<GeneratedImageItem> = withContext(Dispatchers.IO) {
        val snapshot = imagesRef
            .child(userId.toString())
            .child(characterId)
            .orderByChild("createdAt")
            .limitToLast(limit)
            .awaitSingle()

        snapshot.children
            .mapNotNull { it.toGeneratedImageItem(characterId) }
            .sortedByDescending { it.createdAt }
    }

    suspend fun get(userId: Long, characterId: String, imageId: String): GeneratedImageItem? = withContext(Dispatchers.IO) {
        imagesRef
            .child(userId.toString())
            .child(characterId)
            .child(imageId)
            .awaitSingle()
            .toGeneratedImageItem(characterId)
    }

    private fun GeneratedImageItem.toPayload(): Map<String, Any?> = mapOf(
        "id" to id,
        "characterId" to characterId,
        "characterName" to characterName,
        "telegramFileId" to telegramFileId,
        "prompt" to prompt,
        "model" to model,
        "source" to source,
        "createdAt" to createdAt
    )

    private fun DataSnapshot.toGeneratedImageItem(fallbackCharacterId: String): GeneratedImageItem? {
        val id = child("id").getValue(String::class.java) ?: key ?: return null
        val telegramFileId = child("telegramFileId").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: return null
        val characterId = child("characterId").getValue(String::class.java) ?: fallbackCharacterId
        val characterName = child("characterName").getValue(String::class.java).orEmpty()
        val createdAt = child("createdAt").getValue(Long::class.java) ?: 0L

        return GeneratedImageItem(
            id = id,
            characterId = characterId,
            characterName = characterName,
            telegramFileId = telegramFileId,
            prompt = child("prompt").getValue(String::class.java).orEmpty(),
            model = child("model").getValue(String::class.java).orEmpty(),
            source = child("source").getValue(String::class.java).orEmpty(),
            createdAt = createdAt
        )
    }
}
