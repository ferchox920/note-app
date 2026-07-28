package com.noteapp.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryMessageTest {
    @Test
    fun `explains microphone preemption without exposing internals`() {
        assertEquals(
            "El micrófono fue ocupado por una llamada u otra app.",
            recoverableSessionMessage("AUDIO_CLIENT_SILENCED"),
        )
    }

    @Test
    fun `explains force stopped session without inventing a cause`() {
        assertEquals(
            "La grabación se cerró antes de finalizar.",
            recoverableSessionMessage(null),
        )
    }
}
