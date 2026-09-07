package emily.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ConversationCompactorTest {
    @Test
    fun `compaction starts only after the configured threshold`() {
        val compactor = ConversationCompactor()
        val atThreshold = (1..COMPACTION_TRIGGER_TURNS).map { "user" to "turn-$it" }
        val aboveThreshold = atThreshold + ("assistant" to "turn-${COMPACTION_TRIGGER_TURNS + 1}")

        assertTrue(!compactor.shouldCompact(atThreshold))
        assertTrue(compactor.shouldCompact(aboveThreshold))
    }

    @Test
    fun `long conversations compact early without discarding the entire recent window`() {
        val compactor = ConversationCompactor()
        val tooFewTurns = (1..COMPACTION_RECENT_TURNS).map { "user" to "x".repeat(2_000) }
        val longConversation = tooFewTurns + ("assistant" to "x".repeat(2_000))

        assertTrue(!compactor.shouldCompact(tooFewTurns))
        assertTrue(compactor.shouldCompact(longConversation))
    }

    @Test
    fun `compaction pins the exact beginning and keeps the recent tail`() = runBlocking {
        val turns = (1..101).map { index ->
            (if (index % 2 == 0) "assistant" else "user") to "turn-$index"
        }
        val compactor = ConversationCompactor()

        val result = compactor.compact(null, emptyList(), turns) { previous, compacted ->
            assertEquals(null, previous)
            assertEquals((1..61).map { "turn-$it" }, compacted.map { it.second })
            "durable summary"
        }

        assertNotNull(result)
        assertEquals((1..8).map { "turn-$it" }, result.pinnedTurns.map { it.second })
        assertEquals((62..101).map { "turn-$it" }, result.remainingRecentTurns.map { it.second })
        assertEquals("durable summary", result.summary)
    }

    @Test
    fun `incremental compaction preserves pinned turns and merges previous summary`() = runBlocking {
        val pinned = listOf("user" to "the real first turn")
        val turns = (1..105).map { "user" to "new-$it" }
        val compactor = ConversationCompactor()

        val result = compactor.compact("old memory", pinned, turns) { previous, compacted ->
            assertEquals("old memory", previous)
            assertEquals(65, compacted.size)
            "old memory plus new facts"
        }

        assertNotNull(result)
        assertEquals(pinned, result.pinnedTurns)
        assertEquals(40, result.remainingRecentTurns.size)
        assertEquals("new-66", result.remainingRecentTurns.first().second)
    }

    @Test
    fun `memory injects summary while retaining pinned and recent turns`() {
        val memory = ConversationMemory { "character rules" }
        memory.restoreCompaction(
            chatId = 7L,
            summary = "User likes tea.",
            pinnedTurns = listOf("user" to "Hello from the first day"),
            recentTurns = listOf("assistant" to "Latest answer")
        )

        val history = memory.history(7L)

        assertTrue(history.first().second.contains("character rules"))
        assertTrue(history.first().second.contains("User likes tea."))
        assertEquals("Hello from the first day", history[1].second)
        assertEquals("Latest answer", history.last().second)
    }
}
