package com.noteapp.audio

enum class CapturePipeline(
    val id: String,
    val captureSampleRateHz: Int,
    val outputSampleRateHz: Int,
) {
    DIRECT_16_KHZ("direct-16k", 16_000, 16_000),
    NATIVE_48_KHZ_TO_16_KHZ("native-48k-to-16k", 48_000, 16_000),
    ;

    companion object {
        fun fromId(id: String?): CapturePipeline = entries.firstOrNull { it.id == id } ?: DIRECT_16_KHZ
    }
}
