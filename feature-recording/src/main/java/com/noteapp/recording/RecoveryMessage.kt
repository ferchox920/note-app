package com.noteapp.recording

internal fun recoverableSessionMessage(errorCode: String?): String =
    when (errorCode) {
        "AUDIO_CLIENT_SILENCED" ->
            "El micrófono fue ocupado por una llamada u otra app."
        "AUDIO_DEAD_OBJECT", "AUDIO_READ_FAILED" ->
            "Android interrumpió la captura de audio."
        null ->
            "La grabación se cerró antes de finalizar."
        else ->
            "La captura se interrumpió ($errorCode)."
    }
