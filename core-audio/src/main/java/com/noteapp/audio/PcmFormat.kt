package com.noteapp.audio

data class PcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16,
) {
    init {
        require(sampleRateHz > 0)
        require(channelCount == 1) { "The MVP accepts mono capture only" }
        require(bitsPerSample == 16) { "The MVP accepts PCM16 capture only" }
    }

    val bytesPerSecond: Int = sampleRateHz * channelCount * (bitsPerSample / 8)
}

