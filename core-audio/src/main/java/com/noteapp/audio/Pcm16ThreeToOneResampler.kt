package com.noteapp.audio

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Streaming 48 kHz -> 16 kHz PCM16 mono FIR resampler for the capture experiment. */
class Pcm16ThreeToOneResampler {
    private val history = DoubleArray(FILTER_TAPS)
    private var historyIndex = 0
    private var phase = 0

    fun process(input: ByteArray, offset: Int = 0, length: Int = input.size): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= input.size)
        require(length % 2 == 0) { "PCM16 input must contain complete samples" }
        val output = ByteArrayOutputStream(length / DECIMATION_FACTOR + 2)
        var byteIndex = offset
        val end = offset + length
        while (byteIndex < end) {
            val sample = ((input[byteIndex].toInt() and 0xff) or
                (input[byteIndex + 1].toInt() shl 8)).toShort().toDouble()
            history[historyIndex] = sample
            historyIndex = (historyIndex + 1) % FILTER_TAPS
            phase++
            if (phase == DECIMATION_FACTOR) {
                phase = 0
                val filtered = convolve().roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.write(filtered and 0xff)
                output.write((filtered shr 8) and 0xff)
            }
            byteIndex += 2
        }
        return output.toByteArray()
    }

    private fun convolve(): Double {
        var value = 0.0
        coefficients.forEachIndexed { delay, coefficient ->
            val index = (historyIndex - 1 - delay + FILTER_TAPS) % FILTER_TAPS
            value += history[index] * coefficient
        }
        return value
    }

    private companion object {
        const val DECIMATION_FACTOR = 3
        const val FILTER_TAPS = 63
        const val NORMALIZED_CUTOFF = 0.15 // 7.2 kHz at 48 kHz

        val coefficients: DoubleArray = DoubleArray(FILTER_TAPS) { index ->
            val centered = index - (FILTER_TAPS - 1) / 2.0
            val sinc = if (centered == 0.0) {
                2.0 * NORMALIZED_CUTOFF
            } else {
                sin(2.0 * PI * NORMALIZED_CUTOFF * centered) / (PI * centered)
            }
            val hamming = 0.54 - 0.46 * cos(2.0 * PI * index / (FILTER_TAPS - 1))
            sinc * hamming
        }.let { raw ->
            val sum = raw.sum()
            DoubleArray(raw.size) { raw[it] / sum }
        }
    }
}
