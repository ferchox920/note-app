package com.noteapp.asr

data class SpeechInterval(
    val startMs: Long,
    val endMs: Long,
    val startByteOffset: Long,
    val endByteOffset: Long,
)

data class AsrAudioChunk(
    val startMs: Long,
    val endMs: Long,
    val startByteOffset: Long,
    val endByteOffset: Long,
)

data class AsrLabConfig(
    val threadCount: Int = 4,
    val maxChunkMs: Long = 30_000L,
    val maxMergeGapMs: Long = 3_000L,
    val splitOverlapMs: Long = 500L,
) {
    init {
        require(threadCount in 1..16) { "threadCount must be between 1 and 16" }
        require(maxChunkMs in 1_000L..30_000L) { "maxChunkMs must be between 1 s and 30 s" }
        require(maxMergeGapMs in 0L until maxChunkMs) {
            "maxMergeGapMs must be non-negative and shorter than maxChunkMs"
        }
        require(splitOverlapMs in 0L until maxChunkMs) {
            "splitOverlapMs must be non-negative and shorter than maxChunkMs"
        }
    }

    val id: String = "t${threadCount}-c${maxChunkMs / 1_000}s"

    companion object {
        val Default = AsrLabConfig()
        val ThreadSweep = listOf(2, 4, 6, 8).map { AsrLabConfig(threadCount = it) }
        val ChunkSweep = listOf(10_000L, 20_000L, 30_000L).map {
            AsrLabConfig(maxChunkMs = it)
        }
    }
}

object AsrChunkAssembler {
    // Offline refinement uses Whisper's natural 30 s context to amortize the
    // fixed cost of whisper_full. Incremental ASR keeps its independent 4 s
    // windows for visible latency.
    private const val BYTES_PER_MS = 32L // PCM16, mono, 16 kHz

    fun assemble(
        intervals: List<SpeechInterval>,
        config: AsrLabConfig = AsrLabConfig.Default,
    ): List<AsrAudioChunk> {
        val normalized = intervals
            .filter { it.endMs > it.startMs && it.endByteOffset > it.startByteOffset }
            .sortedBy { it.startMs }
        val chunks = mutableListOf<AsrAudioChunk>()
        var pending: AsrAudioChunk? = null

        normalized.forEach { interval ->
            split(interval, config).forEach { candidate ->
                val current = pending
                val canMerge = current != null &&
                    candidate.startMs - current.endMs <= config.maxMergeGapMs &&
                    candidate.endMs - current.startMs <= config.maxChunkMs
                if (canMerge) {
                    pending = current!!.copy(
                        endMs = candidate.endMs,
                        endByteOffset = candidate.endByteOffset,
                    )
                } else {
                    current?.let(chunks::add)
                    pending = candidate
                }
            }
        }
        pending?.let(chunks::add)
        return chunks
    }

    private fun split(
        interval: SpeechInterval,
        config: AsrLabConfig,
    ): List<AsrAudioChunk> {
        if (interval.endMs - interval.startMs <= config.maxChunkMs) {
            return listOf(interval.toChunk())
        }
        val chunks = mutableListOf<AsrAudioChunk>()
        var startMs = interval.startMs
        while (startMs < interval.endMs) {
            val endMs = minOf(startMs + config.maxChunkMs, interval.endMs)
            val startByte = interval.startByteOffset + (startMs - interval.startMs) * BYTES_PER_MS
            val endByte = minOf(
                interval.endByteOffset,
                interval.startByteOffset + (endMs - interval.startMs) * BYTES_PER_MS,
            )
            chunks += AsrAudioChunk(startMs, endMs, startByte, endByte)
            if (endMs == interval.endMs) break
            startMs = endMs - config.splitOverlapMs
        }
        return chunks
    }

    private fun SpeechInterval.toChunk() = AsrAudioChunk(
        startMs = startMs,
        endMs = endMs,
        startByteOffset = startByteOffset,
        endByteOffset = endByteOffset,
    )
}
