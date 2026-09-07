package emily.bot

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleRequestPhotoDecisionArchitectureTest {
    @Test
    fun `chat reply and photo decision use one model request`() {
        val source = File("src/main/kotlin/emily/bot/EmilyVirtualGirlBot.kt").readText()
        val handleChat = source.substringAfter("private suspend fun handleChat(")
            .substringBefore("private fun chatModelFor(")

        assertEquals(
            1,
            Regex("chatService\\.generateReply\\(").findAll(handleChat).count(),
            "handleChat must make exactly one text-model request"
        )
        assertTrue("promptCacheKey = promptCacheKey" in handleChat)
        assertTrue("AutoPhotoDecisionParser.parse(result.text)" in handleChat)
        assertFalse("askPhotoDecisionModel" in source)
        assertFalse("photoDecisionSystem" in source)
    }
}
