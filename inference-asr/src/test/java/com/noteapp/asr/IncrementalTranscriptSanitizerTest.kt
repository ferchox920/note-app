package com.noteapp.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class IncrementalTranscriptSanitizerTest {
    @Test
    fun `preserves normal Spanish text and modest emphasis`() {
        assertEquals(
            "sí sí, debemos revisar el informe antes de enviarlo",
            IncrementalTranscriptSanitizer.sanitize(
                "sí sí, debemos revisar el informe antes de enviarlo",
            ),
        )
    }

    @Test
    fun `rejects a short single-token decoder loop`() {
        val result = IncrementalTranscriptSanitizer.inspect(
            "hola hola hola hola hola mundo",
        )

        assertEquals("", result.text)
        assertEquals(true, result.suppressedRepetition)
    }

    @Test
    fun `rejects a long repeated phrase loop`() {
        assertEquals(
            "",
            IncrementalTranscriptSanitizer.sanitize(
                "no se puede ver no se puede ver no se puede ver no se puede ver",
            ),
        )
    }

    @Test
    fun `does not treat separated recurring words as a consecutive loop`() {
        val text = "hoy revisamos audio y mañana revisamos texto porque revisamos cada etapa"
        assertEquals(text, IncrementalTranscriptSanitizer.sanitize(text))
    }
}
