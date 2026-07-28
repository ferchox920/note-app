package com.noteapp.audio

internal const val ERROR_AUDIO_READ_FAILED = "AUDIO_READ_FAILED"
internal const val ERROR_AUDIO_DEAD_OBJECT = "AUDIO_DEAD_OBJECT"
internal const val ERROR_AUDIO_CLIENT_SILENCED = "AUDIO_CLIENT_SILENCED"

internal enum class CaptureFailureDisposition {
    RECOVERABLE,
    TERMINAL,
}

internal fun captureFailureDisposition(errorCode: String): CaptureFailureDisposition =
    when (errorCode) {
        ERROR_AUDIO_READ_FAILED,
        ERROR_AUDIO_DEAD_OBJECT,
        ERROR_AUDIO_CLIENT_SILENCED,
        -> CaptureFailureDisposition.RECOVERABLE

        else -> CaptureFailureDisposition.TERMINAL
    }
