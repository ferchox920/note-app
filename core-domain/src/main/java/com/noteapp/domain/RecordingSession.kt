package com.noteapp.domain

import java.util.UUID

enum class SessionStatus {
    NEW,
    RECORDING,
    PAUSED,
    RECOVERING,
    COMPLETED,
    FAILED,
    ABORTED,
}

data class RecordingSession(
    val id: String = UUID.randomUUID().toString(),
    val status: SessionStatus = SessionStatus.NEW,
    val durationMs: Long = 0,
    val errorCode: String? = null,
)

sealed interface RecordingIntent {
    data object Start : RecordingIntent
    data object Pause : RecordingIntent
    data object Resume : RecordingIntent
    data object Recover : RecordingIntent
    data object Complete : RecordingIntent
    data object Abort : RecordingIntent
    data class Fail(val errorCode: String) : RecordingIntent
}

fun RecordingSession.reduce(intent: RecordingIntent): RecordingSession = when (intent) {
    RecordingIntent.Start -> transition(SessionStatus.NEW, SessionStatus.RECORDING)
    RecordingIntent.Pause -> transition(SessionStatus.RECORDING, SessionStatus.PAUSED)
    RecordingIntent.Resume -> transition(SessionStatus.PAUSED, SessionStatus.RECORDING)
    RecordingIntent.Recover -> copy(status = SessionStatus.RECOVERING, errorCode = null)
    RecordingIntent.Complete -> when (status) {
        SessionStatus.RECORDING, SessionStatus.PAUSED, SessionStatus.RECOVERING ->
            copy(status = SessionStatus.COMPLETED, errorCode = null)
        else -> this
    }
    RecordingIntent.Abort -> copy(status = SessionStatus.ABORTED, errorCode = null)
    is RecordingIntent.Fail -> copy(status = SessionStatus.FAILED, errorCode = intent.errorCode)
}

private fun RecordingSession.transition(
    expected: SessionStatus,
    target: SessionStatus,
): RecordingSession = if (status == expected) copy(status = target, errorCode = null) else this

