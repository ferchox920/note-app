package com.noteapp.asr

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

data class WhisperSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val noSpeechProbability: Float,
)

data class WhisperNativeTimings(
    val sampleMs: Float,
    val encodeMs: Float,
    val decodeMs: Float,
    val batchMs: Float,
    val promptMs: Float,
)

data class WhisperTranscription(
    val modelId: String,
    val language: String,
    val audioDurationMs: Long,
    val inferenceDurationMs: Long,
    val realTimeFactor: Double,
    val segments: List<WhisperSegment>,
    val nativeTimings: WhisperNativeTimings,
)

class WhisperEngine private constructor(
    private var pointer: Long,
    private val descriptor: WhisperModelDescriptor,
    private val threadCount: Int,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "whisper-inference").apply { priority = Thread.NORM_PRIORITY }
    }.asCoroutineDispatcher()

    suspend fun transcribePcm16(
        pcm: ByteArray,
        offsetMs: Long = 0,
        language: String = "es",
        lowLatency: Boolean = false,
    ): WhisperTranscription = withContext(dispatcher) {
        check(pointer != 0L) { "Whisper engine is released" }
        val samples = Pcm16Decoder.littleEndianToFloat(pcm)
        val audioDurationMs = samples.size * 1_000L / SAMPLE_RATE_HZ
        val started = System.nanoTime()
        val result = WhisperNative.nativeTranscribe(
            pointer = pointer,
            audio = samples,
            threadCount = threadCount,
            language = language,
            lowLatency = lowLatency,
        )
        val inferenceDurationMs = (System.nanoTime() - started) / 1_000_000L
        check(result == 0) { "whisper_full failed with code $result" }

        val segments = List(WhisperNative.nativeSegmentCount(pointer)) { index ->
            WhisperSegment(
                startMs = offsetMs + WhisperNative.nativeSegmentStart(pointer, index) * 10L,
                endMs = offsetMs + WhisperNative.nativeSegmentEnd(pointer, index) * 10L,
                text = WhisperNative.nativeSegmentText(pointer, index),
                noSpeechProbability = WhisperNative.nativeSegmentNoSpeechProbability(pointer, index),
            )
        }
        val timings = WhisperNative.nativeTimings(pointer)
        WhisperTranscription(
            modelId = descriptor.id,
            language = language,
            audioDurationMs = audioDurationMs,
            inferenceDurationMs = inferenceDurationMs,
            realTimeFactor = realTimeFactor(inferenceDurationMs, audioDurationMs),
            segments = segments,
            nativeTimings = WhisperNativeTimings(
                sampleMs = timings.getOrElse(0) { 0f },
                encodeMs = timings.getOrElse(1) { 0f },
                decodeMs = timings.getOrElse(2) { 0f },
                batchMs = timings.getOrElse(3) { 0f },
                promptMs = timings.getOrElse(4) { 0f },
            ),
        )
    }

    suspend fun release() {
        withContext(dispatcher) {
            if (pointer != 0L) {
                WhisperNative.nativeFree(pointer)
                pointer = 0L
            }
        }
        dispatcher.close()
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000

        suspend fun create(
            modelFile: File,
            descriptor: WhisperModelDescriptor,
            requestedThreads: Int = 4,
        ): WhisperEngine {
            val verification = withContext(kotlinx.coroutines.Dispatchers.IO) {
                WhisperModelVerifier.verify(modelFile, descriptor)
            }
            require(verification == ModelVerificationResult.Valid) {
                "Model verification failed: $verification"
            }
            val threads = max(1, min(requestedThreads, Runtime.getRuntime().availableProcessors()))
            val pointer = withContext(kotlinx.coroutines.Dispatchers.IO) {
                WhisperNative.nativeInit(modelFile.absolutePath)
            }
            check(pointer != 0L) { "Unable to initialize whisper.cpp model" }
            return WhisperEngine(pointer, descriptor, threads)
        }

        fun systemInfo(): String = WhisperNative.nativeSystemInfo()

        fun realTimeFactor(inferenceDurationMs: Long, audioDurationMs: Long): Double =
            if (audioDurationMs <= 0) Double.POSITIVE_INFINITY
            else inferenceDurationMs.toDouble() / audioDurationMs
    }
}
