package com.noteapp.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class StablePrefixReconcilerTest {
    @Test
    fun `golden sequence commits only repeated prefix without duplicating it`() {
        val reconciler = StablePrefixReconciler()

        assertEquals(
            IncrementalTranscript("", "hola cómo"),
            reconciler.update("hola cómo"),
        )
        assertEquals(
            IncrementalTranscript("hola cómo", "estás"),
            reconciler.update("Hola, cómo estás"),
        )
        assertEquals(
            IncrementalTranscript("hola cómo estás", "esta mañana"),
            reconciler.update("hola cómo estás esta mañana"),
        )
        assertEquals(
            IncrementalTranscript("hola cómo estás esta mañana temprano", ""),
            reconciler.finalizeSegment("hola cómo estás esta mañana temprano"),
        )
    }

    @Test
    fun `unstable tail may change without rewriting committed words`() {
        val reconciler = StablePrefixReconciler()
        reconciler.update("necesito una cita mañana")
        reconciler.update("necesito una cita mañana")

        val revised = reconciler.update("necesito una cita el martes")

        assertEquals("necesito una cita", revised.stableText)
        assertEquals("el martes", revised.unstableText)
    }

    @Test
    fun `sliding hypotheses stitch their textual overlap without duplication`() {
        val reconciler = StablePrefixReconciler()

        reconciler.update("hola cómo estás hoy")
        val shifted = reconciler.update("estás hoy amigo mío")
        val shiftedAgain = reconciler.update("amigo mío esta tarde")

        assertEquals("hola cómo", shifted.stableText)
        assertEquals("estás hoy amigo mío", shifted.unstableText)
        assertEquals("hola cómo estás hoy", shiftedAgain.stableText)
        assertEquals("amigo mío esta tarde", shiftedAgain.unstableText)
    }

    @Test
    fun `reports a conflict when a shifted window has no usable overlap`() {
        val reconciler = StablePrefixReconciler()
        reconciler.update("uno dos tres cuatro")
        reconciler.update("tres cuatro cinco seis")

        val conflict = reconciler.update("texto completamente distinto")

        assertEquals(true, conflict.stableConflict)
        assertEquals("uno dos", conflict.stableText)
        assertEquals("texto completamente distinto", conflict.unstableText)
    }
}
