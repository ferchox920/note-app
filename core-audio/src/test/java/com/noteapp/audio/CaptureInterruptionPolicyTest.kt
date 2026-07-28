package com.noteapp.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureInterruptionPolicyTest {
    @Test
    fun `mic preemption failures remain recoverable`() {
        listOf(
            ERROR_AUDIO_READ_FAILED,
            ERROR_AUDIO_DEAD_OBJECT,
            ERROR_AUDIO_CLIENT_SILENCED,
        ).forEach { errorCode ->
            assertEquals(
                CaptureFailureDisposition.RECOVERABLE,
                captureFailureDisposition(errorCode),
            )
        }
    }

    @Test
    fun `data integrity failures remain terminal`() {
        assertEquals(
            CaptureFailureDisposition.TERMINAL,
            captureFailureDisposition("AUDIO_READ_ALIGNMENT_FAILED"),
        )
    }
}
