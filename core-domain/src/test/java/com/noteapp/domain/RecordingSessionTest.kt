package com.noteapp.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSessionTest {
    @Test
    fun `start pause resume and complete follow the valid lifecycle`() {
        val completed = RecordingSession()
            .reduce(RecordingIntent.Start)
            .reduce(RecordingIntent.Pause)
            .reduce(RecordingIntent.Resume)
            .reduce(RecordingIntent.Complete)

        assertEquals(SessionStatus.COMPLETED, completed.status)
    }

    @Test
    fun `resume is ignored unless session is paused`() {
        val session = RecordingSession().reduce(RecordingIntent.Resume)

        assertEquals(SessionStatus.NEW, session.status)
    }

    @Test
    fun `failure retains a sanitized error code`() {
        val failed = RecordingSession()
            .reduce(RecordingIntent.Start)
            .reduce(RecordingIntent.Fail("AUDIO_READ_FAILED"))

        assertEquals(SessionStatus.FAILED, failed.status)
        assertEquals("AUDIO_READ_FAILED", failed.errorCode)
    }
}

