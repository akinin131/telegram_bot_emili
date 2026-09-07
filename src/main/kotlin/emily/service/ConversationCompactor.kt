package emily.service

const val COMPACTION_PINNED_TURNS = 8
// Keep the live prompt small. Older context is preserved in the summary and
// pinned opening messages, so it does not need to be re-sent verbatim.
const val COMPACTION_RECENT_TURNS = 40
const val COMPACTION_TRIGGER_TURNS = 48
const val COMPACTION_TRIGGER_CHARS = 24_000
const val COMPACTION_BOOTSTRAP_TURNS = 200
private const val MAX_COMPACTION_SUMMARY_CHARS = 6_000

data class ConversationCompactionResult(
    val summary: String,
    val pinnedTurns: List<Pair<String, String>>,
    val remainingRecentTurns: List<Pair<String, String>>
)

class ConversationCompactor {
    fun shouldCompact(turns: List<Pair<String, String>>): Boolean {
        val conversationTurns = turns.filter { (role, text) ->
            (role == "user" || role == "assistant") && text.isNotBlank()
        }
        if (conversationTurns.size > COMPACTION_TRIGGER_TURNS) return true
        if (conversationTurns.size <= COMPACTION_RECENT_TURNS) return false
        return conversationTurns.sumOf { (_, text) -> text.length } > COMPACTION_TRIGGER_CHARS
    }

    suspend fun compact(
        existingSummary: String?,
        existingPinnedTurns: List<Pair<String, String>>,
        turns: List<Pair<String, String>>,
        summarize: suspend (existingSummary: String?, turnsToCompact: List<Pair<String, String>>) -> String
    ): ConversationCompactionResult? {
        val cleanTurns = turns.filter { (role, text) ->
            (role == "user" || role == "assistant") && text.isNotBlank()
        }
        if (!shouldCompact(cleanTurns)) return null

        val pinned = existingPinnedTurns.ifEmpty { cleanTurns.take(COMPACTION_PINNED_TURNS) }
        val remaining = cleanTurns.takeLast(COMPACTION_RECENT_TURNS)
        val turnsToCompact = cleanTurns.dropLast(COMPACTION_RECENT_TURNS)
        if (turnsToCompact.isEmpty()) return null

        val summary = summarize(existingSummary, turnsToCompact)
            .trim()
            .take(MAX_COMPACTION_SUMMARY_CHARS)
        if (summary.isBlank()) return null

        return ConversationCompactionResult(
            summary = summary,
            pinnedTurns = pinned,
            remainingRecentTurns = remaining
        )
    }
}
