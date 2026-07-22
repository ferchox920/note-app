package com.noteapp.testing

import com.noteapp.domain.RecordingSession
import com.noteapp.domain.SessionStatus

object SessionFixtures {
    fun recording(id: String = "session-test"): RecordingSession = RecordingSession(
        id = id,
        status = SessionStatus.RECORDING,
    )
}

