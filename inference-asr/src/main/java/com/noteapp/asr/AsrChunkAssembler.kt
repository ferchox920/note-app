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

object AsrChunkAssembler {
    // Offline refinement uses Whisper's natural 30 s context to amortize the
    // fixed cost of whisper_full. Incremental ASR keeps its independent 4 s
    // windows for visible latency.
    private const val MAX_CHUNK_MS = 30_000L
    private const val MAX_MERGE_GAP_MS = 3_000L
    private const val SPLIT_OVERLAP_MS = 500L
    private const val BYTES_PER_MS = 32L // PCM16, mono, 16 kHz

    fun assemble(intervals: List<SpeechInterval>): List<AsrAudioChunk> {
        val normalized = intervals
            .filter { it.endMs > it.startMs && it.endByteOffset > it.startByteOffset }
            .sortedBy { it.startMs }
        val chunks = mutableListOf<AsrAudioChunk>()
        var pending: AsrAudioChunk? = null

        normalized.forEach { interval ->
            split(interval).forEach { candidate ->
                val current = pending
                val canMerge = current != null &&
                    candidate.startMs - current.endMs <= MAX_MERGE_GAP_MS &&
                    candidate.endMs - current.startMs <= MAX_CHUNK_MS
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

    private fun split(interval: SpeechInterval): List<AsrAudioChunk> {
        if (interval.endMs - interval.startMs <= MAX_CHUNK_MS) {
            return listOf(interval.toChunk())
        }
        val chunks = mutableListOf<AsrAudioChunk>()
        var startMs = interval.startMs
        while (startMs < interval.endMs) {
            val endMs = minOf(startMs + MAX_CHUNK_MS, interval.endMs)
            val startByte = interval.startByteOffset + (startMs - interval.startMs) * BYTES_PER_MS
            val endByte = minOf(
                interval.endByteOffset,
                interval.startByteOffset + (endMs - interval.startMs) * BYTES_PER_MS,
            )
            chunks += AsrAudioChunk(startMs, endMs, startByte, endByte)
            if (endMs == interval.endMs) break
            startMs = endMs - SPLIT_OVERLAP_MS
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
