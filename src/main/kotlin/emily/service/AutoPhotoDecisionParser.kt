package emily.service

import java.util.Locale
import org.json.JSONObject

internal data class AutoPhotoDecision(
    val prompt: String,
    val bypassTurnGate: Boolean,
    val source: String
)

internal data class ParsedAssistantResponse(
    val text: String,
    val photoDecision: AutoPhotoDecision?
)

internal object AutoPhotoDecisionParser {
    private val decisionMarker = Regex(
        pattern = """\s*\[\[PHOTO_DECISION:\s*(\{.*})\s*]]\s*$""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val legacyPromptMarker = Regex(
        pattern = """\s*\[\[PHOTO_PROMPT:\s*.*?]]\s*$""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun parse(rawText: String): ParsedAssistantResponse {
        val match = decisionMarker.find(rawText)
        if (match == null) {
            return ParsedAssistantResponse(
                text = rawText.replace(legacyPromptMarker, "").trim(),
                photoDecision = null
            )
        }

        val visibleText = rawText.removeRange(match.range).trim()
        val json = runCatching { JSONObject(match.groupValues[1]) }.getOrNull()
            ?: return ParsedAssistantResponse(visibleText, null)

        if (!json.optBoolean("shouldSendPhoto", false)) {
            return ParsedAssistantResponse(visibleText, null)
        }
        if (!json.optBoolean("committedVisualMoment", false)) {
            return ParsedAssistantResponse(visibleText, null)
        }
        if (json.optBoolean("contradictsVisibleText", true)) {
            return ParsedAssistantResponse(visibleText, null)
        }

        val reason = json.optString("reason", "context").lowercase(Locale.ROOT)
        val threshold = if (reason in setOf("outfit", "intimacy", "visual_moment")) 0.68 else 0.82
        if (json.optDouble("confidence", 0.0) < threshold) {
            return ParsedAssistantResponse(visibleText, null)
        }

        val prompt = json.optString("prompt").trim().takeIf { it.isNotBlank() }
            ?: return ParsedAssistantResponse(visibleText, null)
        return ParsedAssistantResponse(
            text = visibleText,
            photoDecision = AutoPhotoDecision(
                prompt = prompt,
                bypassTurnGate = reason in setOf("outfit", "intimacy"),
                source = "auto:single_request:$reason"
            )
        )
    }
}
