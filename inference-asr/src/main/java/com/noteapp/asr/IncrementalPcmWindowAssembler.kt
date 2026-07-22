package com.noteapp.asr

data class IncrementalPcmWindow(
    val startSample: Long,
    val endSample: Long,
    val samples: ShortArray,
)

/**
 * Keeps only the recent PCM needed by pseudo-streaming Whisper windows.
 *
 * With the defaults, the first partial covers 0..3 s and subsequent full windows
 * cover 4 s every 3 s, leaving exactly 1 s of overlap.
 */
class IncrementalPcmWindowAssembler(
    sampleRateHz: Int = 16_000,
    windowDurationMs: Int = 4_000,
    emissionIntervalMs: Int = 3_000,
) {
    private val windowSamples = samplesFor(sampleRateHz, windowDurationMs)
    private val emissionSamples = samplesFor(sampleRateHz, emissionIntervalMs)
    private val ring = ShortArray(windowSamples)
    private var writeIndex = 0
    private var size = 0
    private var totalSamples = 0L
    private var nextEmissionSample = emissionSamples.toLong()

    init {
        require(emissionIntervalMs in 2_000..4_000) {
            "Partial emission interval must be between 2 and 4 seconds"
        }
        require(windowDurationMs >= emissionIntervalMs) {
            "Window must be at least as long as the emission interval"
        }
    }

    fun append(samples: ShortArray): List<IncrementalPcmWindow> {
        if (samples.isEmpty()) return emptyList()
        val ready = mutableListOf<IncrementalPcmWindow>()
        var sourceOffset = 0
        while (sourceOffset < samples.size) {
            val untilEmission = (nextEmissionSample - totalSamples)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val count = minOf(samples.size - sourceOffset, untilEmission)
            write(samples, sourceOffset, count)
            sourceOffset += count
            totalSamples += count
            if (totalSamples == nextEmissionSample) {
                ready += snapshot()
                nextEmissionSample += emissionSamples
            }
        }
        return ready
    }

    fun currentWindow(): IncrementalPcmWindow? = if (size == 0) null else snapshot()

    fun reset() {
        writeIndex = 0
        size = 0
        totalSamples = 0L
        nextEmissionSample = emissionSamples.toLong()
    }

    private fun write(source: ShortArray, offset: Int, length: Int) {
        repeat(length) { index ->
            ring[writeIndex] = source[offset + index]
            writeIndex = (writeIndex + 1) % ring.size
            if (size < ring.size) size++
        }
    }

    private fun snapshot(): IncrementalPcmWindow {
        val copy = ShortArray(size)
        val oldestIndex = if (size == ring.size) writeIndex else 0
        repeat(size) { index ->
            copy[index] = ring[(oldestIndex + index) % ring.size]
        }
        return IncrementalPcmWindow(
            startSample = totalSamples - size,
            endSample = totalSamples,
            samples = copy,
        )
    }

    private fun samplesFor(sampleRateHz: Int, durationMs: Int): Int {
        require(sampleRateHz > 0 && durationMs > 0)
        val product = sampleRateHz.toLong() * durationMs
        require(product % 1_000L == 0L) { "Duration must map to an exact sample count" }
        return (product / 1_000L).toInt()
    }
}
