package com.noteapp.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class Pcm16ThreeToOneResamplerTest {
    @Test
    fun `produces exactly one sample for each three input samples`() {
        val input = pcm16(ShortArray(48_000) { 1_000 })
        val output = Pcm16ThreeToOneResampler().process(input)

        assertEquals(16_000 * 2, output.size)
        val last = decodeLast(output)
        assertTrue(last in 995..1_005)
    }

    @Test
    fun `streaming chunks produce the same bytes as one complete buffer`() {
        val input = pcm16(ShortArray(4_803) { index -> ((index * 37) % 20_000).toShort() })
        val whole = Pcm16ThreeToOneResampler().process(input)
        val streaming = Pcm16ThreeToOneResampler()
        val first = streaming.process(input, 0, 2_002)
        val second = streaming.process(input, 2_002, input.size - 2_002)

        assertArrayEquals(whole, first + second)
    }

    @Test
    fun `low pass suppresses frequencies above the 16 kHz nyquist limit`() {
        val passBand = resampleSine(1_000.0)
        val stopBand = resampleSine(12_000.0)

        assertTrue(rms(passBand) > 5_000.0)
        assertTrue(rms(stopBand) < rms(passBand) * 0.05)
    }

    private fun resampleSine(frequencyHz: Double): ShortArray {
        val input = ShortArray(48_000) { index ->
            (12_000 * sin(2.0 * PI * frequencyHz * index / 48_000.0)).toInt().toShort()
        }
        val bytes = Pcm16ThreeToOneResampler().process(pcm16(input))
        return ShortArray(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
        }.drop(100).toShortArray()
    }

    private fun rms(samples: ShortArray): Double = sqrt(
        samples.sumOf { sample -> sample.toDouble() * sample } / samples.size,
    )

    private fun pcm16(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
    }

    private fun decodeLast(bytes: ByteArray): Int {
        val index = bytes.size - 2
        return ((bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
    }
}
