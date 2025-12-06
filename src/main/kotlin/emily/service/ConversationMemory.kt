package emily.service

import emily.resources.Strings
import java.util.concurrent.ConcurrentHashMap

private const val MAX_CONTEXT_MESSAGES = 20
private val noiseRegex = Regex(
    """^([/#][\p{L}\p{N}_@-]+.*|\s*)$""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

class ConversationMemory(
    private val systemPromptProvider: () -> String
) {
    private val contexts = ConcurrentHashMap<Long, MutableList<Pair<String, String>>>()

    fun setSystem(chatId: Long, content: String) {
        val conversationEntries = contexts.computeIfAbsent(chatId) { mutableListOf("system" to "") }
        if (conversationEntries.isEmpty() || conversationEntries.first().first != "system") {
            conversationEntries.add(0, "system" to content)
        } else {
            conversationEntries[0] = "system" to content
        }
    }

    fun history(chatId: Long): List<Pair<String, String>> =
        contexts[chatId]?.toList().orEmpty()

    fun initIfNeeded(chatId: Long) {
        val contextEntries = contexts.computeIfAbsent(chatId) { mutableListOf() }
        if (contextEntries.isEmpty()) {
            contextEntries += "system" to systemPromptProvider().orEmpty()
        } else if (contextEntries.first().first != "system") {
            contextEntries.add(0, "system" to systemPromptProvider().orEmpty())
        }
    }

    fun reset(chatId: Long) {
        contexts.remove(chatId)
    }

    fun append(chatId: Long, role: String, content: String) {
        val context = contexts.computeIfAbsent(chatId) { mutableListOf() }
        if (role == "user" && shouldSkip(content)) return
        context += role to content
        contexts[chatId] = trim(context)
    }

    fun autoClean(chatId: Long) {
        contexts[chatId]?.let { existing ->
            var seenSystem = false
            val cleaned = mutableListOf<Pair<String, String>>()
            for ((role, content) in existing) {
                if (role == "system") {
                    if (!seenSystem) {
                        cleaned += "system" to content
                        seenSystem = true
                    }
                    continue
                }
                if (shouldSkip(content)) continue
                cleaned += role to content
            }
            if (!seenSystem) cleaned.add(0, "system" to systemPromptProvider())
            contexts[chatId] = trim(cleaned)
        }
    }

    private fun trim(history: List<Pair<String, String>>): MutableList<Pair<String, String>> {
        val system = history.firstOrNull { it.first == "system" }
        val rest = history.filter { it.first != "system" }
        val trimmedRest = if (rest.size > MAX_CONTEXT_MESSAGES) rest.takeLast(MAX_CONTEXT_MESSAGES) else rest
        return buildList {
            if (system != null) add(system)
            addAll(trimmedRest)
        }.toMutableList()
    }

    private fun shouldSkip(text: String): Boolean {
        val t = text.trim()
        if (t.startsWith("#WEBAPP", ignoreCase = true)) return true
        return noiseRegex.matches(t)
    }
}

fun defaultSystemPrompt(): String = Strings.get("system.prompt.default")
