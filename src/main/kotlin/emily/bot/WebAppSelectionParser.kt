package emily.bot

import emily.app.WebAppStory
import emily.resources.Strings
import java.util.Base64
class WebAppSelectionParser(
    private val defaultPersona: String
) {

    data class HiddenWebAppData(
        val characterId: Int,
        val storyId: Int,
        val styleCode: Int
    )

    private val zeroWidthSpace: Char = '\u200B'
    private val zeroWidthNonJoiner: Char = '\u200C'
    private val startMark: String = "\u2063\u200D"
    private val endMark: String = "\u200D\u2063"

    fun decodeHiddenData(text: String): HiddenWebAppData? {
        val startIdx = text.indexOf(startMark)
        if (startIdx == -1) return null
        val endIdx = text.indexOf(endMark, startIdx + startMark.length)
        if (endIdx == -1) return null

        val encoded = text.substring(startIdx + startMark.length, endIdx)
        if (encoded.isEmpty()) return null

        val bits = StringBuilder(encoded.length)
        for (ch in encoded) {
            when (ch) {
                zeroWidthSpace -> bits.append('0')
                zeroWidthNonJoiner -> bits.append('1')
                else -> return null
            }
        }

        if (bits.length % 8 != 0) return null
        val byteCount = bits.length / 8
        val bytes = ByteArray(byteCount)
        for (i in 0..<byteCount) {
            val byteStr = bits.substring(i * 8, i * 8 + 8)
            bytes[i] = byteStr.toInt(2).toByte()
        }

        val outerB64 = bytes.toString(Charsets.UTF_8)
        val payloadBytes = runCatching { Base64.getDecoder().decode(outerB64) }.getOrElse { return null }
        val payload = String(payloadBytes, Charsets.UTF_8)

        val parts = payload.split("|")
        if (parts.size < 3) return null

        val charId = parts[0].toIntOrNull() ?: return null
        val storyId = parts[1].toIntOrNull() ?: return null
        val styleCode = parts[2].toIntOrNull() ?: return null

        return HiddenWebAppData(
            characterId = charId,
            storyId = storyId,
            styleCode = styleCode
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun resolvePersona(
        characterId: Int,
        styleCode: Int
    ): String {
        return when (characterId) {
            1 -> Strings.get("persona.charlotte")
            2 -> Strings.get("persona.angela")
            3 -> Strings.get("persona.vika")

            else -> defaultPersona
        }
    }

    private val charlotteStory1Prompt = Strings.get("story.charlotte.prompt1")
    private val charlotteStory2Prompt = Strings.get("story.charlotte.prompt2")
    private val angelaStory3Prompt = Strings.get("story.angela.prompt3")
    private val angelaStory4Prompt = Strings.get("story.angela.prompt4")
    private val vikaStory5Prompt = Strings.get("story.vika.prompt5")
    private val vikaStory6Prompt = Strings.get("story.vika.prompt6")

    private val storyPrompts: Map<Int, Map<Int, String>> = mapOf(
        1 to mapOf(
            1 to charlotteStory1Prompt,
            2 to charlotteStory2Prompt
        ),
        2 to mapOf(
            3 to angelaStory3Prompt,
            4 to angelaStory4Prompt
        ),
        3 to mapOf(
            5 to vikaStory5Prompt,
            6 to vikaStory6Prompt
        )
    )

    fun resolveStoryPrompt(
        characterId: Int,
        storyId: Int
    ): String {
        return storyPrompts[characterId]?.get(storyId).orEmpty()
    }

    fun parseWebAppMessage(text: String): WebAppStory? {
        val clean = text.trim()

        val characterName = Regex("""Персонаж:\s*(.+)""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        val storyTitle = Regex("""История:\s*(.+)""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        if (characterName.isNullOrBlank() || storyTitle.isNullOrBlank()) {
            println("❌ parseWebAppMessage: не нашли персонажа или историю")
            return null
        }

        val fullStoryText = Regex("""full_story_text:\s*(.+)""", RegexOption.DOT_MATCHES_ALL)
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: run {
                clean.substringAfter("История:", "")
                    .substringAfter(storyTitle, "")
                    .substringBefore("⏰")
                    .substringBefore("📊")
                    .trim()
            }

        val styleStr = Regex("""style:\s*([^\n\r]+)""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        val style = styleStr?.toIntOrNull()

        val characterPersonality = Regex("""characterPersonality:\s*([^\n\r]+)""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        val storyDescription = Regex("""storyDescription:\s*([^\n\r]+)""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        return WebAppStory(
            characterName = characterName,
            storyTitle = storyTitle,
            style = style,
            characterPersonality = characterPersonality,
            storyDescription = storyDescription,
            fullStoryText = fullStoryText
        )
    }
}
