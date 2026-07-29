package com.noteapp.asr

internal object WhisperNative {
    init {
        System.loadLibrary("noteapp_whisper")
    }

    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(pointer: Long)
    external fun nativeTranscribe(
        pointer: Long,
        audio: FloatArray,
        threadCount: Int,
        language: String,
        lowLatency: Boolean,
    ): Int
    external fun nativeSegmentCount(pointer: Long): Int
    external fun nativeSegmentText(pointer: Long, index: Int): String
    external fun nativeSegmentStart(pointer: Long, index: Int): Long
    external fun nativeSegmentEnd(pointer: Long, index: Int): Long
    external fun nativeSegmentNoSpeechProbability(pointer: Long, index: Int): Float
    external fun nativeTimings(pointer: Long): FloatArray
    external fun nativeSystemInfo(): String
}
