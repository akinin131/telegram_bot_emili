package emily.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class AutoPhotoDecisionParserTest {
    @Test
    fun `extracts text and accepted photo decision from one response`() {
        val parsed = AutoPhotoDecisionParser.parse(
            """
            Я уже надела платье. Смотри ✨
            [[PHOTO_DECISION: {"shouldSendPhoto":true,"confidence":0.91,"reason":"outfit","committedVisualMoment":true,"contradictsVisibleText":false,"prompt":"adult woman in an elegant red dress, mirror selfie"}]]
            """.trimIndent()
        )

        assertEquals("Я уже надела платье. Смотри ✨", parsed.text)
        val decision = assertNotNull(parsed.photoDecision)
        assertEquals("adult woman in an elegant red dress, mirror selfie", decision.prompt)
        assertEquals(true, decision.bypassTurnGate)
        assertEquals("auto:single_request:outfit", decision.source)
    }

    @Test
    fun `keeps text and skips photo when model marks it unnecessary`() {
        val parsed = AutoPhotoDecisionParser.parse(
            "Просто поболтаем. [[PHOTO_DECISION: {\"shouldSendPhoto\":false}]]"
        )

        assertEquals("Просто поболтаем.", parsed.text)
        assertNull(parsed.photoDecision)
    }

    @Test
    fun `rejects photo that contradicts visible reply`() {
        val parsed = AutoPhotoDecisionParser.parse(
            """
            Покажу платье позже.
            [[PHOTO_DECISION: {"shouldSendPhoto":true,"confidence":0.99,"reason":"outfit","committedVisualMoment":false,"contradictsVisibleText":true,"prompt":"woman in a dress"}]]
            """.trimIndent()
        )

        assertEquals("Покажу платье позже.", parsed.text)
        assertNull(parsed.photoDecision)
    }

    @Test
    fun `rejects weak contextual photo decision`() {
        val parsed = AutoPhotoDecisionParser.parse(
            """
            Я сижу у окна.
            [[PHOTO_DECISION: {"shouldSendPhoto":true,"confidence":0.75,"reason":"context","committedVisualMoment":true,"contradictsVisibleText":false,"prompt":"woman near a window"}]]
            """.trimIndent()
        )

        assertEquals("Я сижу у окна.", parsed.text)
        assertNull(parsed.photoDecision)
    }

    @Test
    fun `strips legacy prompt without sending an unvalidated photo`() {
        val parsed = AutoPhotoDecisionParser.parse(
            "Ответ. [[PHOTO_PROMPT: woman taking a selfie]]"
        )

        assertEquals("Ответ.", parsed.text)
        assertNull(parsed.photoDecision)
    }
}
