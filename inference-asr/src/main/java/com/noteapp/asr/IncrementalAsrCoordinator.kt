package com.noteapp.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

data class IncrementalInferenceResult(
    val text: String,
    val inferenceDurationMs: Long,
    val realTimeFactor: Double,
    val suppressedRepetition: Boolean = false,
    val nativeTimings: WhisperNativeTimings = WhisperNativeTimings(
        sampleMs = 0f,
        encodeMs = 0f,
        decodeMs = 0f,
        batchMs = 0f,
        promptMs = 0f,
    ),
)

fun interface IncrementalPcmTranscriber {
    suspend fun transcribe(pcm16: ByteArray, offsetMs: Long): IncrementalInferenceResult
}

interface IncrementalAsrSession {
    val state: StateFlow<IncrementalAsrState>
    val requiresVad: Boolean

    fun onPcm16(
        pcm16: ByteArray,
        length: Int = pcm16.size,
        speechActive: Boolean,
        endpointDetected: Boolean,
        streamEndMs: Long,
    )

    fun endSegment(streamEndMs: Long)
    fun reportError(errorCode: String)
    suspend fun shutdown(drain: Boolean)
}

data class IncrementalTranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class IncrementalInferenceMetric(
    val sequence: Int,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val final: Boolean,
    val audioDurationMs: Long,
    val inferenceDurationMs: Long,
    val visibleLatencyMs: Long,
    val realTimeFactor: Double,
    val reusedResult: Boolean = false,
    val suppressedRepetition: Boolean = false,
    val nativeTimings: WhisperNativeTimings = WhisperNativeTimings(
        sampleMs = 0f,
        encodeMs = 0f,
        decodeMs = 0f,
        batchMs = 0f,
        promptMs = 0f,
    ),
)

data class IncrementalAsrState(
    val enabled: Boolean = true,
    val running: Boolean = false,
    val stableText: String = "",
    val unstableText: String = "",
    val queueDepth: Int = 0,
    val droppedPartialCount: Long = 0,
    val partialCount: Int = 0,
    val stableConflictCount: Int = 0,
    val suppressedRepetitionCount: Int = 0,
    val timeToFirstTextMs: Long? = null,
    val lastVisibleLatencyMs: Long? = null,
    val lastRealTimeFactor: Double? = null,
    val finalizedSegments: List<IncrementalTranscriptSegment> = emptyList(),
    val inferenceMetrics: List<IncrementalInferenceMetric> = persistentListOf(),
    val errorCode: String? = null,
)

internal fun List<IncrementalInferenceMetric>.appendMetric(
    metric: IncrementalInferenceMetric,
): List<IncrementalInferenceMetric> =
    if (this is PersistentList<IncrementalInferenceMetric>) {
        add(metric)
    } else {
        toPersistentList().add(metric)
    }

internal data class IncrementalInferenceTask(
    val window: IncrementalPcmWindow,
    val segmentStartMs: Long,
    val final: Boolean,
    val streamEndMs: Long,
    val enqueuedAtNanos: Long,
)

internal data class QueueOfferResult(
    val accepted: Boolean,
    val droppedPartials: Int,
)

internal class RecentPcmSamples(private val capacity: Int) {
    private val values = ShortArray(capacity)
    private var writeIndex = 0
    private var size = 0

    init {
        require(capacity > 0)
    }

    fun append(samples: ShortArray) {
        samples.forEach { sample ->
            values[writeIndex] = sample
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) size++
        }
    }

    fun snapshot(): ShortArray {
        val result = ShortArray(size)
        val oldestIndex = if (size == capacity) writeIndex else 0
        repeat(size) { index ->
            result[index] = values[(oldestIndex + index) % capacity]
        }
        return result
    }

    fun clear() {
        writeIndex = 0
        size = 0
    }
}

/** Bounded queue that sacrifices stale partials but never silently evicts a final task. */
internal class FinalAwareInferenceQueue(private val capacity: Int = 2) {
    private val pending = ArrayDeque<IncrementalInferenceTask>(capacity)

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun offer(task: IncrementalInferenceTask): QueueOfferResult {
        var dropped = 0
        if (task.final) {
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                if (!iterator.next().final) {
                    iterator.remove()
                    dropped++
                }
            }
            if (pending.size == capacity) return QueueOfferResult(false, dropped)
        } else if (pending.size == capacity) {
            val iterator = pending.iterator()
            var removed = false
            while (iterator.hasNext()) {
                if (!iterator.next().final) {
                    iterator.remove()
                    dropped++
                    removed = true
                    break
                }
            }
            if (!removed) return QueueOfferResult(false, dropped + 1)
        }
        pending.addLast(task)
        return QueueOfferResult(true, dropped)
    }

    @Synchronized
    fun poll(): IncrementalInferenceTask? = pending.pollFirst()

    @Synchronized
    fun size(): Int = pending.size
}

class IncrementalAsrCoordinator(
    scope: CoroutineScope,
    private val transcriber: IncrementalPcmTranscriber,
    private val sampleRateHz: Int = WhisperEngine.SAMPLE_RATE_HZ,
    private val endpointFinalizationGraceMs: Long = ENDPOINT_FINALIZATION_GRACE_MS,
    private val nanoTime: () -> Long = System::nanoTime,
    initialState: IncrementalAsrState = IncrementalAsrState(),
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
    override val requiresVad: Boolean = true

    private val assembler = IncrementalPcmWindowAssembler(sampleRateHz = sampleRateHz)
    private val reconciler = StablePrefixReconciler()
    private val preRoll = RecentPcmSamples(samplesForMs(PRE_ROLL_MS))
    private val queue = FinalAwareInferenceQueue(QUEUE_CAPACITY)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val startedAtNanos = nanoTime()
    private val worker: Job = scope.launch { processQueue() }
    private var accepting = true
    private var segmentActive = false
    private var segmentStartMs = 0L
    private var cachedPartial: CachedPartial? = null
    private var endpointCandidateMs: Long? = null

    init {
        require(endpointFinalizationGraceMs >= 0)
    }

    /** Called on the capture thread after VAD processed the same normalized PCM. */
    override fun onPcm16(
        pcm16: ByteArray,
        length: Int,
        speechActive: Boolean,
        endpointDetected: Boolean,
        streamEndMs: Long,
    ) {
        if (!accepting || length == 0) return
        require(length in 0..pcm16.size && length % 2 == 0) {
            "PCM16 input must contain complete samples"
        }
        val samples = decodeLittleEndian(pcm16, length)
        if (!segmentActive && speechActive) {
            segmentActive = true
            endpointCandidateMs = null
            assembler.reset()
            val buffered = preRoll.snapshot()
            val pcmDurationMs = samples.size * 1_000L / sampleRateHz
            segmentStartMs = (streamEndMs - pcmDurationMs - buffered.size * 1_000L / sampleRateHz)
                .coerceAtLeast(0L)
            enqueuePartials(assembler.append(buffered))
            preRoll.clear()
        }

        if (segmentActive) {
            enqueuePartials(assembler.append(samples))
            if (speechActive) endpointCandidateMs = null
            if (endpointDetected) endpointCandidateMs = streamEndMs
            val candidateMs = endpointCandidateMs
            if (
                !speechActive &&
                candidateMs != null &&
                streamEndMs - candidateMs >= endpointFinalizationGraceMs
            ) {
                enqueueFinal(streamEndMs)
            }
        } else {
            preRoll.append(samples)
        }
    }

    /** Forces a final refinement at pause/finish even if hangover was incomplete. */
    override fun endSegment(streamEndMs: Long) {
        if (accepting && segmentActive) enqueueFinal(streamEndMs)
    }

    override fun reportError(errorCode: String) {
        mutableState.update { it.copy(errorCode = errorCode) }
    }

    override suspend fun shutdown(drain: Boolean) {
        accepting = false
        if (drain) {
            while (mutableState.value.running || queue.size() > 0) delay(10)
        }
        worker.cancel()
        worker.join()
        signal.close()
        mutableState.update { it.copy(enabled = false, running = false, queueDepth = queue.size()) }
    }

    private fun enqueuePartials(windows: List<IncrementalPcmWindow>) {
        windows.forEach { window -> offer(window, final = false, streamEndMs = 0L) }
    }

    private fun enqueueFinal(streamEndMs: Long) {
        val window = assembler.currentWindow()
        if (window != null) offer(window, final = true, streamEndMs = streamEndMs)
        assembler.reset()
        segmentActive = false
        endpointCandidateMs = null
        preRoll.clear()
    }

    private fun offer(window: IncrementalPcmWindow, final: Boolean, streamEndMs: Long) {
        val result = queue.offer(
            IncrementalInferenceTask(
                window = window,
                segmentStartMs = segmentStartMs,
                final = final,
                streamEndMs = streamEndMs,
                enqueuedAtNanos = nanoTime(),
            ),
        )
        mutableState.update { current ->
            current.copy(
                queueDepth = queue.size(),
                droppedPartialCount = current.droppedPartialCount + result.droppedPartials,
                errorCode = if (!result.accepted && final) ERROR_FINAL_QUEUE_OVERFLOW else current.errorCode,
            )
        }
        if (result.accepted) signal.trySend(Unit)
        if (final && streamEndMs < segmentStartMs) {
            mutableState.update { it.copy(errorCode = ERROR_INVALID_TIMELINE) }
        }
    }

    private suspend fun processQueue() {
        while (currentCoroutineContext().isActive) {
            signal.receiveCatching().getOrNull() ?: break
            while (true) {
                val task = queue.poll() ?: break
                mutableState.update { it.copy(running = true, queueDepth = queue.size()) }
                val cached = cachedPartial?.takeIf { it.matches(task) }
                runCatching {
                    cached?.result ?: run {
                        val pcm = encodeLittleEndian(task.window.samples)
                        val offsetMs = segmentStartMs(task)
                        transcriber.transcribe(pcm, offsetMs)
                    }
                }.onSuccess { result ->
                    if (task.final) {
                        cachedPartial = null
                    } else {
                        cachedPartial = CachedPartial.from(task, result)
                    }
                    applyResult(task, result, reusedResult = cached != null)
                }.onFailure { failure ->
                    if (task.final) cachedPartial = null
                    mutableState.update {
                        it.copy(errorCode = failure.message ?: ERROR_INFERENCE_FAILED)
                    }
                }
                mutableState.update { it.copy(running = false, queueDepth = queue.size()) }
            }
        }
    }

    private fun applyResult(
        task: IncrementalInferenceTask,
        result: IncrementalInferenceResult,
        reusedResult: Boolean,
    ) {
        val transcript = if (task.final) {
            reconciler.finalizeSegment(result.text)
        } else {
            reconciler.update(result.text)
        }
        val visibleLatencyMs = (nanoTime() - task.enqueuedAtNanos) / 1_000_000L
        mutableState.update { current ->
            val completed = if (task.final && transcript.stableText.isNotBlank()) {
                current.finalizedSegments + IncrementalTranscriptSegment(
                    startMs = segmentStartMs(task),
                    endMs = task.streamEndMs,
                    text = transcript.stableText,
                )
            } else {
                current.finalizedSegments
            }
            val completedText = completed.joinToString(" ") { it.text }.trim()
            val currentStable = if (task.final) "" else transcript.stableText
            val stable = listOf(completedText, currentStable)
                .filter(String::isNotBlank)
                .joinToString(" ")
            val hasFirstText = stable.isNotBlank() || transcript.unstableText.isNotBlank()
            val windowStartMs = segmentStartMs(task)
            val audioDurationMs = task.window.samples.size * 1_000L / sampleRateHz
            val metric = IncrementalInferenceMetric(
                sequence = current.inferenceMetrics.size,
                windowStartMs = windowStartMs,
                windowEndMs = windowStartMs + audioDurationMs,
                final = task.final,
                audioDurationMs = audioDurationMs,
                inferenceDurationMs = if (reusedResult) 0L else result.inferenceDurationMs,
                visibleLatencyMs = visibleLatencyMs,
                realTimeFactor = if (reusedResult) 0.0 else result.realTimeFactor,
                reusedResult = reusedResult,
                suppressedRepetition = result.suppressedRepetition,
                nativeTimings = if (reusedResult) ZERO_NATIVE_TIMINGS else result.nativeTimings,
            )
            current.copy(
                stableText = stable,
                unstableText = if (task.final) "" else transcript.unstableText,
                finalizedSegments = completed,
                inferenceMetrics = current.inferenceMetrics.appendMetric(metric),
                partialCount = current.partialCount + if (task.final) 0 else 1,
                stableConflictCount = current.stableConflictCount + if (transcript.stableConflict) 1 else 0,
                suppressedRepetitionCount = current.suppressedRepetitionCount +
                    if (result.suppressedRepetition) 1 else 0,
                timeToFirstTextMs = current.timeToFirstTextMs ?: if (hasFirstText) {
                    (nanoTime() - startedAtNanos) / 1_000_000L
                } else {
                    null
                },
                lastVisibleLatencyMs = visibleLatencyMs,
                lastRealTimeFactor = result.realTimeFactor,
            )
        }
    }

    private fun segmentStartMs(task: IncrementalInferenceTask): Long =
        task.segmentStartMs + task.window.startSample * 1_000L / sampleRateHz

    private fun samplesForMs(durationMs: Int): Int = sampleRateHz * durationMs / 1_000

    private fun decodeLittleEndian(bytes: ByteArray, length: Int): ShortArray = ShortArray(length / 2) { index ->
        val low = bytes[index * 2].toInt() and 0xff
        val high = bytes[index * 2 + 1].toInt()
        ((high shl 8) or low).toShort()
    }

    private fun encodeLittleEndian(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toInt().toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
    }

    private data class CachedPartial(
        val segmentStartMs: Long,
        val windowStartSample: Long,
        val windowEndSample: Long,
        val samples: ShortArray,
        val result: IncrementalInferenceResult,
    ) {
        fun matches(task: IncrementalInferenceTask): Boolean =
            task.final &&
                segmentStartMs == task.segmentStartMs &&
                windowStartSample == task.window.startSample &&
                windowEndSample == task.window.endSample &&
                samples.contentEquals(task.window.samples)

        companion object {
            fun from(
                task: IncrementalInferenceTask,
                result: IncrementalInferenceResult,
            ) = CachedPartial(
                segmentStartMs = task.segmentStartMs,
                windowStartSample = task.window.startSample,
                windowEndSample = task.window.endSample,
                samples = task.window.samples,
                result = result,
            )
        }
    }

    private companion object {
        const val PRE_ROLL_MS = 200
        const val QUEUE_CAPACITY = 2
        const val ENDPOINT_FINALIZATION_GRACE_MS = 700L
        val ZERO_NATIVE_TIMINGS = WhisperNativeTimings(0f, 0f, 0f, 0f, 0f)
        const val ERROR_INFERENCE_FAILED = "INCREMENTAL_ASR_INFERENCE_FAILED"
        const val ERROR_FINAL_QUEUE_OVERFLOW = "INCREMENTAL_ASR_FINAL_QUEUE_OVERFLOW"
        const val ERROR_INVALID_TIMELINE = "INCREMENTAL_ASR_INVALID_TIMELINE"
    }
}
