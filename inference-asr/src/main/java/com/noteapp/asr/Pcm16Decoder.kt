package com.noteapp.asr

object Pcm16Decoder {
    fun littleEndianToFloat(pcm: ByteArray): FloatArray {
        require(pcm.size % BYTES_PER_SAMPLE == 0) { "PCM16 byte count must be even" }
        return FloatArray(pcm.size / BYTES_PER_SAMPLE) { index ->
            val byteIndex = index * BYTES_PER_SAMPLE
            val low = pcm[byteIndex].toInt() and 0xff
            val high = pcm[byteIndex + 1].toInt()
            val sample = (high shl 8) or low
            sample / PCM16_SCALE
        }
    }

    private const val BYTES_PER_SAMPLE = 2
    private const val PCM16_SCALE = 32_768f
}

