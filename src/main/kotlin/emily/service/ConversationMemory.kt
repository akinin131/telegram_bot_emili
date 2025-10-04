package emily.service

import java.util.concurrent.ConcurrentHashMap

private const val MAX_CONTEXT_MESSAGES = 20
private val noiseRegex = Regex("""^([/#][\p{L}\p{N}_@-]+.*|\s*)$""", RegexOption.IGNORE_CASE)

class ConversationMemory(
    private val systemPromptProvider: () -> String
) {
    private val contexts = ConcurrentHashMap<Long, MutableList<Pair<String, String>>>()

    fun initIfNeeded(chatId: Long) {
        contexts.computeIfAbsent(chatId) { mutableListOf("system" to systemPromptProvider()) }
    }

    fun reset(chatId: Long) {
        contexts.remove(chatId)
    }

    fun append(chatId: Long, role: String, content: String) {
        val context = contexts.computeIfAbsent(chatId) { mutableListOf("system" to systemPromptProvider()) }
        if (role == "user" && shouldSkip(content)) return
        context += role to content
        contexts[chatId] = trim(context)
    }

    fun history(chatId: Long): List<Pair<String, String>> =
        contexts[chatId]?.let { trim(it) } ?: emptyList()

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

    private fun shouldSkip(text: String): Boolean = noiseRegex.matches(text.trim())
}

fun defaultSystemPrompt(): String =
    "Ты — Эмили, виртуальная девушка: тёплая, игривая, поддерживающая и немного пошлая! " +
        "любишь немного флиртовать. Отвечай дружелюбно и запоминай контекст разговора."
