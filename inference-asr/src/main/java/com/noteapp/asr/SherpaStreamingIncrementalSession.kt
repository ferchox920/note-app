package com.noteapp.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ceil

private data class StreamingPcmFrame(
    val pcm16: ByteArray,
    val streamEndMs: Long,
    val enqueuedAtNanos: Long,
)

class SherpaStreamingIncrementalSession private constructor(
    scope: CoroutineScope,
    private val decoder: StreamingDecoder,
    initialState: IncrementalAsrState,
    initialStreamEndMs: Long,
    private val sampleRateHz: Int,
    private val nanoTime: () -> Long,
) : IncrementalAsrSession {
    private val mutableState = MutableStateFlow(
        initialState.copy(
            enabled = true,
            running = false,
            unstableText = "",
            queueDepth = 0,
        ),
    )
    override val state: StateFlow<IncrementalAsrState> = mutableState.asStateFlow()
    override val requiresVad: Boolean = false

    private val frames = Channel<StreamingPcmFrame>(capacity = QUEUE_CAPACITY)
    private val worker: Job = scope.launch { processFrames() }
    private val targetFrameBytes = sampleRateHz * 2 * TARGET_FRAME_MS / 1_000
    private val pendingPcm = ByteArray(targetFrameBytes)
    private var accepting = true
    private var queuedFrames = 0
    private var pendingLength = 0
    private var pendingStartMs = 0L
    private var lastHypothesis = ""
    private var segmentStartMs = maxOf(
        initialStreamEndMs,
        initialState.finalizedSegments.lastOrNull()?.endMs ?: 0L,
    )
    private var latestStreamEndMs = segmentStartMs
    private var latestInputEndMs = segmentStartMs

    @Synchronized
    override fun onPcm16(
        pcm16: ByteArray,
        length: Int,
        speechActive: Boolean,
        endpointDetected: Boolean,
        streamEndMs: Long,
    ) {
        if (!accepting || length <= 0) return
        val inputDurationMs = length * 1_000L / (sampleRateHz * 2L)
        if (
            length % 2 != 0 ||
            length > pcm16.size ||
            inputDurationMs <= 0 ||
            streamEndMs <= latestInputEndMs
        ) {
            mutableState.update { it.copy(errorCode = ERROR_INVALID_STREAM_FRAME) }
            return
        }
        var inputOffset = 0
        if (pendingLength == 0) {
            pendingStartMs = (streamEndMs - inputDurationMs).coerceAtLeast(latestInputEndMs)
        }
        while (inputOffset < length && accepting) {
            val copied = minOf(targetFrameBytes - pendingLength, length - inputOffset)
            pcm16.copyInto(
                destination = pendingPcm,
                destinationOffset = pendingLength,
                startIndex = inputOffset,
                endIndex = inputOffset + copied,
            )
            pendingLength += copied
            inputOffset += copied
            if (pendingLength == targetFrameBytes) {
                enqueuePendingFrame()
            }
        }
        latestInputEndMs = streamEndMs
    }

    override fun endSegment(streamEndMs: Long) {
        // Pause/resume must preserve the online transducer state. The native
        // endpoint detector or shutdown finalizes the active hypothesis.
    }

    override fun reportError(errorCode: String) {
        mutableState.update { it.copy(errorCode = errorCode) }
    }

    override suspend fun shutdown(drain: Boolean) {
        synchronized(this) {
            if (drain && accepting && pendingLength > 0) enqueuePendingFrame()
            accepting = false
            pendingLength = 0
        }
        if (drain) {
            frames.close()
            worker.join()
        } else {
            frames.close()
            worker.cancelAndJoin()
        }
        mutableState.update {
            it.copy(enabled = false, running = false, queueDepth = 0)
        }
    }

    private fun enqueuePendingFrame() {
        if (pendingLength == 0) return
        val frameDurationMs = pendingLength * 1_000L / (sampleRateHz * 2L)
        val frameEndMs = pendingStartMs + frameDurationMs
        val sent = frames.trySend(
            StreamingPcmFrame(
                pcm16 = pendingPcm.copyOf(pendingLength),
                streamEndMs = frameEndMs,
                enqueuedAtNanos = nanoTime(),
            ),
        ).isSuccess
        if (sent) {
            queuedFrames++
            mutableState.update { it.copy(queueDepth = queuedFrames) }
            pendingStartMs = frameEndMs
            pendingLength = 0
        } else {
            accepting = false
            mutableState.update {
                it.copy(
                    errorCode = ERROR_QUEUE_OVERFLOW,
                    queueDepth = queuedFrames,
                )
            }
        }
    }

    private suspend fun processFrames() {
        var drained = false
        try {
            for (frame in frames) {
                if (!currentCoroutineContext().isActive) break
                processFrame(frame)
                synchronized(this) {
                    queuedFrames = (queuedFrames - 1).coerceAtLeast(0)
                    mutableState.update {
                        it.copy(running = false, queueDepth = queuedFrames)
                    }
                }
            }
            drained = true
            finishInput()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            mutableState.update {
                it.copy(errorCode = failure.message ?: ERROR_INFERENCE_FAILED)
            }
        } catch (failure: LinkageError) {
            mutableState.update {
                it.copy(errorCode = failure.message ?: ERROR_INFERENCE_FAILED)
            }
        } finally {
            if (!drained) {
                mutableState.update { it.copy(running = false) }
            }
            runCatching { decoder.close() }
        }
    }

    private fun processFrame(frame: StreamingPcmFrame) {
        mutableState.update { it.copy(running = true) }
        val audioDurationMs = frame.pcm16.size * 1_000L / (sampleRateHz * 2L)
        val frameStartMs = (frame.streamEndMs - audioDurationMs).coerceAtLeast(0L)
        val samples = Pcm16Decoder.littleEndianToFloat(frame.pcm16)
        val inferenceStarted = nanoTime()
        decoder.acceptWaveform(samples, sampleRateHz)
        decoder.decodeAvailable()
        val currentText = decoder.text()
        val endpoint = decoder.isEndpoint()
        val inferenceNanos = (nanoTime() - inferenceStarted).coerceAtLeast(0L)
        val inferenceDurationMs = maxOf(1L, ceil(inferenceNanos / 1_000_000.0).toLong())
        val visibleLatencyMs = maxOf(
            0L,
            (nanoTime() - frame.enqueuedAtNanos) / 1_000_000L,
        )
        latestStreamEndMs = frame.streamEndMs

        val hypothesisChanged = currentText.isNotBlank() && currentText != lastHypothesis
        if (hypothesisChanged) lastHypothesis = currentText
        mutableState.update { current ->
            val metric = IncrementalInferenceMetric(
                sequence = current.inferenceMetrics.size,
                windowStartMs = frameStartMs,
                windowEndMs = frame.streamEndMs,
                final = endpoint,
                audioDurationMs = audioDurationMs,
                inferenceDurationMs = inferenceDurationMs,
                visibleLatencyMs = visibleLatencyMs,
                realTimeFactor = WhisperEngine.realTimeFactor(
                    inferenceDurationMs,
                    audioDurationMs,
                ),
            )
            current.copy(
                unstableText = currentText,
                partialCount = current.partialCount + if (hypothesisChanged) 1 else 0,
                timeToFirstTextMs = current.timeToFirstTextMs ?: if (currentText.isNotBlank()) {
                    (frame.streamEndMs - segmentStartMs).coerceAtLeast(0L)
                } else {
                    null
                },
                lastVisibleLatencyMs = visibleLatencyMs,
                lastRealTimeFactor = metric.realTimeFactor,
                inferenceMetrics = current.inferenceMetrics + metric,
            )
        }

        if (endpoint) {
            finalizeHypothesis(currentText, frame.streamEndMs)
            decoder.reset()
        }
    }

    private fun finishInput() {
        decoder.inputFinished()
        decoder.decodeAvailable()
        finalizeHypothesis(decoder.text(), latestStreamEndMs)
    }

    private fun finalizeHypothesis(text: String, streamEndMs: Long) {
        val finalText = text.trim()
        mutableState.update { current ->
            val segments = if (finalText.isNotBlank()) {
                current.finalizedSegments + IncrementalTranscriptSegment(
                    startMs = segmentStartMs.coerceAtMost(streamEndMs),
                    endMs = streamEndMs,
                    text = finalText,
                )
            } else {
                current.finalizedSegments
            }
            current.copy(
                stableText = segments.joinToString(" ") { it.text }.trim(),
                unstableText = "",
                finalizedSegments = segments,
            )
        }
        segmentStartMs = streamEndMs
        lastHypothesis = ""
    }

    companion object {
        private const val QUEUE_CAPACITY = 64
        private const val TARGET_FRAME_MS = 100
        private const val ERROR_QUEUE_OVERFLOW = "STREAMING_ASR_QUEUE_OVERFLOW"
        private const val ERROR_INVALID_STREAM_FRAME = "STREAMING_ASR_INVALID_FRAME"
        private const val ERROR_INFERENCE_FAILED = "STREAMING_ASR_INFERENCE_FAILED"

        fun create(
            scope: CoroutineScope,
            modelDirectory: File,
            descriptor: SherpaStreamingModelDescriptor,
            requestedThreads: Int = 4,
            initialState: IncrementalAsrState = IncrementalAsrState(),
            initialStreamEndMs: Long = 0L,
            sampleRateHz: Int = WhisperEngine.SAMPLE_RATE_HZ,
            nanoTime: () -> Long = System::nanoTime,
        ): SherpaStreamingIncrementalSession {
            val verification = SherpaStreamingModelVerifier.verify(modelDirectory, descriptor)
            require(verification == null) {
                "Model verification failed for ${verification?.artifact?.fileName}: ${verification?.result}"
            }
            return SherpaStreamingIncrementalSession(
                scope = scope,
                decoder = SherpaOnlineStreamingDecoder(
                    modelDirectory = modelDirectory,
                    descriptor = descriptor,
                    threadCount = requestedThreads,
                ),
                initialState = initialState,
                initialStreamEndMs = initialStreamEndMs,
                sampleRateHz = sampleRateHz,
                nanoTime = nanoTime,
            )
        }

        internal fun createForTest(
            scope: CoroutineScope,
            decoder: StreamingDecoder,
            initialState: IncrementalAsrState = IncrementalAsrState(),
            initialStreamEndMs: Long = 0L,
            sampleRateHz: Int = WhisperEngine.SAMPLE_RATE_HZ,
            nanoTime: () -> Long = System::nanoTime,
        ) = SherpaStreamingIncrementalSession(
            scope = scope,
            decoder = decoder,
            initialState = initialState,
            initialStreamEndMs = initialStreamEndMs,
            sampleRateHz = sampleRateHz,
            nanoTime = nanoTime,
        )
    }
}
