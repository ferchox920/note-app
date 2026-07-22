package com.noteapp.vad

import java.io.Closeable
import kotlin.math.max

data class VadSpeechSegment(
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val startByteOffset: Long,
    val endByteOffset: Long,
)

data class VadProcessingResult(
    val closedSegments: List<VadSpeechSegment>,
    val speechActive: Boolean,
    val processedDurationMs: Long,
)

class VadSegmenter(
    private val detector: FrameVoiceDetector,
    private val minimumSpeechMs: Int = 60,
    private val hangoverMs: Int = 300,
    private val preRollMs: Int = 200,
    initialProcessedFrames: Long = 0,
    initialSequence: Int = 0,
    initialLastClosedEndFrame: Long = 0,
) : Closeable {
    private val frameBytes = detector.frameSizeSamples * PCM16_BYTES_PER_SAMPLE
    private val frameDurationMs = detector.frameSizeSamples * 1_000 / detector.sampleRateHz
    private val minimumSpeechFrames = framesForDuration(minimumSpeechMs)
    private val hangoverFrames = framesForDuration(hangoverMs)
    private val preRollFrames = framesForDuration(preRollMs)
    private val pendingFrame = ByteArray(frameBytes)

    private var pendingBytes = 0
    private var processedFrames = initialProcessedFrames
    private var consecutiveSpeechFrames = 0
    private var candidateStartFrame: Long? = null
    private var activeStartFrame: Long? = null
    private var consecutiveSilenceFrames = 0
    private var nextSequence = initialSequence
    private var lastClosedEndFrame = initialLastClosedEndFrame

    val processedDurationMs: Long
        get() = processedFrames * frameDurationMs

    val speechActive: Boolean
        get() = activeStartFrame != null

    init {
        require(frameDurationMs in 10..40) { "VAD frames must be between 10 and 40 ms" }
        require(minimumSpeechMs >= frameDurationMs)
        require(hangoverMs >= frameDurationMs)
        require(preRollMs >= 0)
        require(initialProcessedFrames >= 0)
        require(initialSequence >= 0)
        require(initialLastClosedEndFrame in 0..initialProcessedFrames)
    }

    fun process(buffer: ByteArray, offset: Int, length: Int): VadProcessingResult {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        val closed = mutableListOf<VadSpeechSegment>()
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            val copied = minOf(frameBytes - pendingBytes, remaining)
            buffer.copyInto(pendingFrame, pendingBytes, sourceOffset, sourceOffset + copied)
            pendingBytes += copied
            sourceOffset += copied
            remaining -= copied
            if (pendingBytes == frameBytes) {
                processFrame(pendingFrame)?.let(closed::add)
                pendingBytes = 0
            }
        }
        return VadProcessingResult(closed, speechActive, processedDurationMs)
    }

    fun endCurrentStream(): VadProcessingResult {
        val closed = closeActiveSegment(processedFrames)?.let(::listOf).orEmpty()
        resetDetectionState()
        return VadProcessingResult(closed, speechActive = false, processedDurationMs)
    }

    private fun processFrame(frame: ByteArray): VadSpeechSegment? {
        val voiced = detector.isSpeech(frame)
        var closed: VadSpeechSegment? = null
        if (activeStartFrame == null) {
            if (voiced) {
                if (consecutiveSpeechFrames == 0) candidateStartFrame = processedFrames
                consecutiveSpeechFrames++
                if (consecutiveSpeechFrames >= minimumSpeechFrames) {
                    activeStartFrame = max(
                        lastClosedEndFrame,
                        requireNotNull(candidateStartFrame) - preRollFrames,
                    )
                    consecutiveSilenceFrames = 0
                }
            } else {
                consecutiveSpeechFrames = 0
                candidateStartFrame = null
            }
        } else if (voiced) {
            consecutiveSilenceFrames = 0
        } else {
            consecutiveSilenceFrames++
            if (consecutiveSilenceFrames >= hangoverFrames) {
                closed = closeActiveSegment(processedFrames + 1)
                resetDetectionState()
            }
        }
        processedFrames++
        return closed
    }

    private fun closeActiveSegment(endFrameExclusive: Long): VadSpeechSegment? {
        val startFrame = activeStartFrame ?: return null
        if (endFrameExclusive <= startFrame) return null
        return VadSpeechSegment(
            sequence = nextSequence++,
            startMs = startFrame * frameDurationMs,
            endMs = endFrameExclusive * frameDurationMs,
            startByteOffset = startFrame * frameBytes,
            endByteOffset = endFrameExclusive * frameBytes,
        ).also { lastClosedEndFrame = endFrameExclusive }
    }

    private fun resetDetectionState() {
        consecutiveSpeechFrames = 0
        candidateStartFrame = null
        activeStartFrame = null
        consecutiveSilenceFrames = 0
    }

    private fun framesForDuration(durationMs: Int): Int =
        max(1, (durationMs + frameDurationMs - 1) / frameDurationMs)

    override fun close() = detector.close()

    private companion object {
        const val PCM16_BYTES_PER_SAMPLE = 2
    }
}
