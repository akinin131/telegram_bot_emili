package emily.data

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.text.orEmpty

class StorySelectionRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val selectionsRef by lazy { database.getReference("storySelections") }

    suspend fun save(selection: StorySelection) = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "userId" to selection.userId,
            "characterName" to selection.characterName,
            "characterAppearance" to selection.characterAppearance,
            "characterPersonality" to selection.characterPersonality,
            "storyTitle" to selection.storyTitle,
            "storyDescription" to selection.storyDescription,
            "full_story_text" to selection.full_story_text,
            "style" to selection.style,
            "updatedAt" to selection.updatedAt
        )
        selectionsRef.child(selection.userId.toString()).setValueAsync(payload)
    }

    suspend fun get(userId: Long): StorySelection? = withContext(Dispatchers.IO) {
        val snapshot = selectionsRef.child(userId.toString()).awaitSingle()
        if (!snapshot.exists()) return@withContext null
        StorySelection(
            userId = userId,
            characterName = snapshot.child("characterName").getValue(String::class.java).orEmpty(),
            characterAppearance = snapshot.child("characterAppearance").getValue(String::class.java),
            characterPersonality = snapshot.child("characterPersonality").getValue(String::class.java),
            storyTitle = snapshot.child("storyTitle").getValue(String::class.java).orEmpty(),
            storyDescription = snapshot.child("storyDescription").getValue(String::class.java),
            full_story_text = snapshot.child("full_story_text").getValue(String::class.java),
            style = snapshot.child("style").getValue(String::class.java),
            updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: System.currentTimeMillis()
        )
    }
}
