package emily.data

import com.google.firebase.database.FirebaseDatabase
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UserSettingsRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val settingsRef by lazy { database.getReference("userSettings") }
    private val selectedCharacterKey = "selectedCharacter"

    suspend fun getLanguage(userId: Long): String? = withContext(Dispatchers.IO) {
        val snapshot = settingsRef.child(userId.toString()).child("language").awaitSingle()
        snapshot.getValue(String::class.java)?.trim()?.lowercase(Locale.ROOT)
    }

    suspend fun setLanguage(userId: Long, language: String): Any? = withContext(Dispatchers.IO) {
        val normalized = normalizeLanguage(language)
        val payload = mapOf(
            "language" to normalized,
            "updatedAt" to System.currentTimeMillis()
        )
        settingsRef.child(userId.toString()).updateChildrenAsync(payload)
    }

    suspend fun resolveLanguage(userId: Long, telegramLanguageCode: String?): String = withContext(Dispatchers.IO) {
        val stored = getLanguage(userId)
        if (!stored.isNullOrBlank()) {
            return@withContext normalizeLanguage(stored)
        }
        val detected = detectDefaultLanguage(telegramLanguageCode)
        setLanguage(userId, detected)
        return@withContext detected
    }

    suspend fun getSelectedCharacter(userId: Long): String? = withContext(Dispatchers.IO) {
        val snapshot = settingsRef.child(userId.toString()).child(selectedCharacterKey).awaitSingle()
        snapshot.getValue(String::class.java)?.trim()?.lowercase(Locale.ROOT)
    }

    suspend fun setSelectedCharacter(userId: Long, characterId: String): Any? = withContext(Dispatchers.IO) {
        val normalized = characterId.trim().lowercase(Locale.ROOT)
        val payload = mapOf(
            selectedCharacterKey to normalized,
            "updatedAt" to System.currentTimeMillis()
        )
        settingsRef.child(userId.toString()).updateChildrenAsync(payload)
    }

    private fun detectDefaultLanguage(telegramLanguageCode: String?): String {
        val raw = telegramLanguageCode?.trim().orEmpty()
        if (raw.isBlank()) return "en"

        val normalized = raw.lowercase(Locale.ROOT).replace('_', '-')
        val parts = normalized.split('-').filter { it.isNotBlank() }
        val lang = parts.firstOrNull().orEmpty()
        val region = parts.getOrNull(1)?.uppercase(Locale.ROOT).orEmpty()

        if (lang in setOf("ru", "uk", "be", "kk", "ky", "uz", "tg", "hy", "az")) return "ru"
        if (region in setOf("RU", "BY", "KZ", "UZ", "KG", "TJ", "AM", "AZ")) return "ru"

        return "en"
    }

    private fun normalizeLanguage(value: String?): String {
        val v = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (v == "ru") "ru" else "en"
    }
}
