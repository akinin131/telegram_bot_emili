package emily.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import emily.domain.StoryScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class CustomStoryAccess(
    val userId: Long,
    val storySlotsTotal: Int,
    val storySlotsUsed: Int,
    val updatedAt: Long
) {
    val storySlotsLeft: Int
        get() = (storySlotsTotal - storySlotsUsed).coerceAtLeast(0)
}

data class CustomStory(
    val id: String,
    val userId: Long,
    val characterId: String,
    val title: String,
    val shortDescription: String,
    val setup: String,
    val openingLine: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toScenario(): StoryScenario = StoryScenario(
        id = id,
        title = title,
        shortDescription = shortDescription,
        setup = setup,
        systemInstructions = setup,
        openingLine = openingLine,
        characterIds = setOf(characterId)
    )
}

class CustomStoryRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    companion object {
        const val FREE_STORY_SLOTS = 30
    }

    private val accessRef by lazy { database.getReference("customStoryAccess") }
    private val promoRef by lazy { database.getReference("customStoryPromoRedemptions") }
    private val storiesRef by lazy { database.getReference("customStories") }

    suspend fun getAccess(userId: Long): CustomStoryAccess = withContext(Dispatchers.IO) {
        val snapshot = accessRef.child(userId.toString()).awaitSingle()
        var access = snapshot.toAccess(userId)
        if (access.storySlotsTotal < FREE_STORY_SLOTS) {
            access = access.copy(
                storySlotsTotal = FREE_STORY_SLOTS,
                updatedAt = System.currentTimeMillis()
            )
            accessRef.child(userId.toString()).setValueAsync(access.toPayload())
        }
        access
    }

    suspend fun grantPack(userId: Long, storySlots: Int): CustomStoryAccess = withContext(Dispatchers.IO) {
        val current = accessRef.child(userId.toString()).awaitSingle().toAccess(userId)
        val updated = current.copy(
            storySlotsTotal = current.storySlotsTotal + storySlots,
            updatedAt = System.currentTimeMillis()
        )
        accessRef.child(userId.toString()).setValueAsync(updated.toPayload())
        updated
    }

    suspend fun redeemPromo(userId: Long, promoCode: String, storySlots: Int): CustomStoryAccess? = withContext(Dispatchers.IO) {
        val normalizedCode = promoCode.trim().uppercase()
        val redemptionRef = promoRef.child(normalizedCode).child(userId.toString())
        if (redemptionRef.awaitSingle().exists()) {
            return@withContext null
        }

        val access = grantPack(userId, storySlots)
        redemptionRef.setValueAsync(mapOf(
            "userId" to userId,
            "promoCode" to normalizedCode,
            "storySlots" to storySlots,
            "createdAt" to System.currentTimeMillis()
        ))
        access
    }

    suspend fun createStory(
        userId: Long,
        characterId: String,
        title: String,
        shortDescription: String,
        setup: String,
        openingLine: String
    ): CustomStory = withContext(Dispatchers.IO) {
        val current = accessRef.child(userId.toString()).awaitSingle().toAccess(userId)
        require(current.storySlotsLeft > 0) { "No custom story slots left" }

        val now = System.currentTimeMillis()
        val rawKey = storiesRef.child(userId.toString()).push().key ?: "custom_$now"
        val key = rawKey.lowercase(Locale.ROOT)
        val story = CustomStory(
            id = "custom_$key",
            userId = userId,
            characterId = characterId,
            title = title,
            shortDescription = shortDescription,
            setup = setup,
            openingLine = openingLine,
            createdAt = now,
            updatedAt = now
        )

        storiesRef.child(userId.toString()).child(key).setValueAsync(story.toPayload())
        val updatedAccess = current.copy(
            storySlotsUsed = current.storySlotsUsed + 1,
            updatedAt = now
        )
        accessRef.child(userId.toString()).setValueAsync(updatedAccess.toPayload())
        story
    }

    private suspend fun resolveStoryChild(userId: Long, storyId: String): DataSnapshot? = withContext(Dispatchers.IO) {
        val key = storyId.removePrefix("custom_")
        val ref = storiesRef.child(userId.toString())

        val direct = ref.child(key).awaitSingle()
        if (direct.exists()) return@withContext direct

        val all = ref.awaitSingle()
        for (child in all.children) {
            val id = child.child("id").getValue(String::class.java)
            if (id != null && id.equals(storyId, ignoreCase = true)) return@withContext child
        }
        null
    }

    suspend fun deleteStory(userId: Long, storyId: String) = withContext(Dispatchers.IO) {
        val child = resolveStoryChild(userId, storyId)
        if (child != null) {
            storiesRef.child(userId.toString()).child(child.key).removeValueAsync()
        }

        val current = accessRef.child(userId.toString()).awaitSingle().toAccess(userId)
        val updated = current.copy(
            storySlotsUsed = (current.storySlotsUsed - 1).coerceAtLeast(0),
            updatedAt = System.currentTimeMillis()
        )
        accessRef.child(userId.toString()).setValueAsync(updated.toPayload())
    }

    suspend fun updateStory(
        userId: Long,
        storyId: String,
        title: String,
        shortDescription: String,
        setup: String,
        openingLine: String
    ) = withContext(Dispatchers.IO) {
        val child = resolveStoryChild(userId, storyId) ?: return@withContext
        val now = System.currentTimeMillis()
        val updates = mapOf(
            "title" to title,
            "shortDescription" to shortDescription,
            "setup" to setup,
            "openingLine" to openingLine,
            "updatedAt" to now
        )
        storiesRef.child(userId.toString()).child(child.key).updateChildrenAsync(updates)
    }

    suspend fun listStories(userId: Long, characterId: String): List<CustomStory> = withContext(Dispatchers.IO) {
        val snapshot = storiesRef
            .child(userId.toString())
            .orderByChild("characterId")
            .equalTo(characterId)
            .awaitSingle()

        snapshot.children
            .mapNotNull { it.toCustomStory(userId) }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun getStory(userId: Long, storyId: String): CustomStory? = withContext(Dispatchers.IO) {
        val child = resolveStoryChild(userId, storyId)
        child?.toCustomStory(userId)
    }

    private fun DataSnapshot.toAccess(userId: Long): CustomStoryAccess {
        return CustomStoryAccess(
            userId = child("userId").getValue(Long::class.java) ?: userId,
            storySlotsTotal = child("storySlotsTotal").getValue(Long::class.java)?.toInt()
                ?: child("imageSlotsTotal").getValue(Long::class.java)?.toInt()
                ?: 0,
            storySlotsUsed = child("storySlotsUsed").getValue(Long::class.java)?.toInt()
                ?: child("imageSlotsUsed").getValue(Long::class.java)?.toInt()
                ?: 0,
            updatedAt = child("updatedAt").getValue(Long::class.java) ?: 0L
        )
    }

    private fun CustomStoryAccess.toPayload(): Map<String, Any?> = mapOf(
        "userId" to userId,
        "storySlotsTotal" to storySlotsTotal,
        "storySlotsUsed" to storySlotsUsed,
        "updatedAt" to updatedAt
    )

    private fun CustomStory.toPayload(): Map<String, Any?> = mapOf(
        "id" to id,
        "userId" to userId,
        "characterId" to characterId,
        "title" to title,
        "shortDescription" to shortDescription,
        "setup" to setup,
        "openingLine" to openingLine,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    private fun DataSnapshot.toCustomStory(userId: Long): CustomStory? {
        if (!exists()) return null
        val id = (child("id").getValue(String::class.java) ?: "custom_${key ?: return null}").lowercase(Locale.ROOT)
        val characterId = child("characterId").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: return null
        val title = child("title").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: return null
        val shortDescription = child("shortDescription").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: ""
        val setup = child("setup").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: return null
        val openingLine = child("openingLine").getValue(String::class.java)?.takeIf { it.isNotBlank() }
            ?: "Начнём твою историю?"
        val createdAt = child("createdAt").getValue(Long::class.java) ?: 0L
        val updatedAt = child("updatedAt").getValue(Long::class.java) ?: createdAt

        return CustomStory(
            id = id,
            userId = child("userId").getValue(Long::class.java) ?: userId,
            characterId = characterId,
            title = title,
            shortDescription = shortDescription,
            setup = setup,
            openingLine = openingLine,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
