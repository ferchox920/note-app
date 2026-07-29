package com.noteapp.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.noteapp.security.SessionArtifactStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class SherpaStreamingLabConfig(
    val threadCount: Int = 4,
    val frameMs: Int = 100,
) {
    init {
        require(threadCount > 0)
        require(frameMs in 20..1_000)
    }

    val id: String = "streaming-t${threadCount}-f${frameMs}ms"
}

data class SherpaStreamingLabResult(
    val modelId: String,
    val capturePipelineId: String,
    val benchmarkConfigId: String,
    val threadCount: Int,
    val frameMs: Int,
    val audioDurationMs: Long,
    val inferenceDurationMs: Long,
    val timeToFirstTextMs: Long?,
    val firstTextAudioMs: Long?,
    val realTimeFactor: Double,
    val partialUpdateCount: Int,
    val endpointCount: Int,
    val decodePassCount: Int,
    val peakPssKb: Int,
    val maximumThermalStatus: Int,
    val maximumBatteryTemperatureC: Double?,
    val transcript: String,
)

internal interface StreamingDecoder : AutoCloseable {
    fun acceptWaveform(samples: FloatArray, sampleRateHz: Int)
    fun decodeAvailable(): Int
    fun text(): String
    fun isEndpoint(): Boolean
    fun reset()
    fun inputFinished()
    override fun close()
}

internal class SherpaOnlineStreamingDecoder(
    modelDirectory: File,
    descriptor: SherpaStreamingModelDescriptor,
    threadCount: Int,
) : StreamingDecoder {
    private val recognizer: OnlineRecognizer
    private var stream: OnlineStream

    init {
        val transducer = OnlineTransducerModelConfig(
            encoder = File(modelDirectory, "encoder.onnx").absolutePath,
            decoder = File(modelDirectory, "decoder.onnx").absolutePath,
            joiner = File(modelDirectory, "joiner.onnx").absolutePath,
        )
        val model = OnlineModelConfig(
            transducer = transducer,
            tokens = File(modelDirectory, "tokens.txt").absolutePath,
            numThreads = max(1, min(threadCount, Runtime.getRuntime().availableProcessors())),
            debug = false,
            provider = "cpu",
            modelType = descriptor.modelType,
        )
        recognizer = OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = WhisperEngine.SAMPLE_RATE_HZ,
                    featureDim = 80,
                    dither = 0f,
                ),
                modelConfig = model,
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0f),
                    rule2 = EndpointRule(true, 1.0f, 0f),
                    rule3 = EndpointRule(false, 0f, 20f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            ),
        )
        stream = recognizer.createStream()
    }

    override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
        stream.acceptWaveform(samples, sampleRateHz)
    }

    override fun decodeAvailable(): Int {
        var count = 0
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
            count++
        }
        return count
    }

    override fun text(): String = recognizer.getResult(stream).text.trim()

    override fun isEndpoint(): Boolean = recognizer.isEndpoint(stream)

    override fun reset() {
        recognizer.reset(stream)
    }

    override fun inputFinished() {
        stream.inputFinished()
    }

    override fun close() {
        stream.release()
        recognizer.release()
    }
}

internal data class StreamingDecodeResult(
    val transcript: String,
    val audioDurationMs: Long,
    val inferenceDurationMs: Long,
    val timeToFirstTextMs: Long?,
    val firstTextAudioMs: Long?,
    val partialUpdateCount: Int,
    val endpointCount: Int,
    val decodePassCount: Int,
    val finalizedTexts: List<String>,
)

internal object StreamingDecodeLoop {
    fun run(
        pcm16: ByteArray,
        decoder: StreamingDecoder,
        sampleRateHz: Int = WhisperEngine.SAMPLE_RATE_HZ,
        frameMs: Int = 100,
        nanoTime: () -> Long = System::nanoTime,
    ): StreamingDecodeResult {
        require(pcm16.size % 2 == 0) { "PCM16 input must contain complete samples" }
        val frameSamples = sampleRateHz * frameMs / 1_000
        require(frameSamples > 0)
        val totalSamples = pcm16.size / 2
        val finalized = mutableListOf<String>()
        var sampleOffset = 0
        var partialUpdateCount = 0
        var endpointCount = 0
        var decodePassCount = 0
        var lastText = ""
        var timeToFirstTextMs: Long? = null
        var firstTextAudioMs: Long? = null
        val started = nanoTime()

        fun observeText(processedSamples: Int): String {
            val text = decoder.text()
            if (text.isNotBlank() && text != lastText) {
                partialUpdateCount++
                lastText = text
                if (timeToFirstTextMs == null) {
                    timeToFirstTextMs = (nanoTime() - started) / 1_000_000L
                    firstTextAudioMs = processedSamples * 1_000L / sampleRateHz
                }
            }
            return text
        }

        while (sampleOffset < totalSamples) {
            val count = min(frameSamples, totalSamples - sampleOffset)
            decoder.acceptWaveform(decodePcm16(pcm16, sampleOffset, count), sampleRateHz)
            sampleOffset += count
            decodePassCount += decoder.decodeAvailable()
            val current = observeText(sampleOffset)
            if (decoder.isEndpoint()) {
                if (current.isNotBlank()) finalized += current
                endpointCount++
                decoder.reset()
                lastText = ""
            }
        }

        decoder.inputFinished()
        decodePassCount += decoder.decodeAvailable()
        val tail = observeText(totalSamples)
        if (tail.isNotBlank()) finalized += tail
        val inferenceDurationMs = (nanoTime() - started) / 1_000_000L
        return StreamingDecodeResult(
            transcript = finalized.joinToString(" ").trim(),
            audioDurationMs = totalSamples * 1_000L / sampleRateHz,
            inferenceDurationMs = inferenceDurationMs,
            timeToFirstTextMs = timeToFirstTextMs,
            firstTextAudioMs = firstTextAudioMs,
            partialUpdateCount = partialUpdateCount,
            endpointCount = endpointCount,
            decodePassCount = decodePassCount,
            finalizedTexts = finalized,
        )
    }

    private fun decodePcm16(
        pcm16: ByteArray,
        sampleOffset: Int,
        sampleCount: Int,
    ): FloatArray = FloatArray(sampleCount) { index ->
        val byteIndex = (sampleOffset + index) * 2
        val low = pcm16[byteIndex].toInt() and 0xff
        val high = pcm16[byteIndex + 1].toInt()
        ((high shl 8) or low).toShort() / 32768f
    }
}

class SherpaStreamingLabRunner(
    context: Context,
    private val artifactStore: SessionArtifactStore,
) {
    private val appContext = context.applicationContext
    private val runMutex = Mutex()

    suspend fun transcribeSession(
        sessionDirectory: File,
        modelDirectory: File,
        descriptor: SherpaStreamingModelDescriptor,
        config: SherpaStreamingLabConfig = SherpaStreamingLabConfig(),
    ): SherpaStreamingLabResult {
        check(runMutex.tryLock()) { "ASR_ALREADY_RUNNING" }
        return try {
            transcribeSessionLocked(sessionDirectory, modelDirectory, descriptor, config)
        } finally {
            runMutex.unlock()
        }
    }

    private suspend fun transcribeSessionLocked(
        sessionDirectory: File,
        modelDirectory: File,
        descriptor: SherpaStreamingModelDescriptor,
        config: SherpaStreamingLabConfig,
    ): SherpaStreamingLabResult {
        val verification = withContext(Dispatchers.IO) {
            SherpaStreamingModelVerifier.verify(modelDirectory, descriptor)
        }
        require(verification == null) {
            "Model verification failed for ${verification?.artifact?.fileName}: ${verification?.result}"
        }
        val input = withContext(Dispatchers.IO) { loadInput(sessionDirectory) }
        val performanceSampler = DevicePerformanceSampler(appContext)
        val samplerJob = performanceSampler.start(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        return try {
            val decoded = withContext(Dispatchers.Default) {
                SherpaOnlineStreamingDecoder(
                    modelDirectory = modelDirectory,
                    descriptor = descriptor,
                    threadCount = config.threadCount,
                ).use { decoder ->
                    StreamingDecodeLoop.run(
                        pcm16 = input.pcm,
                        decoder = decoder,
                        frameMs = config.frameMs,
                    )
                }
            }
            val performance = performanceSampler.finish(samplerJob)
            SherpaStreamingLabResult(
                modelId = descriptor.id,
                capturePipelineId = input.capturePipelineId,
                benchmarkConfigId = config.id,
                threadCount = config.threadCount,
                frameMs = config.frameMs,
                audioDurationMs = decoded.audioDurationMs,
                inferenceDurationMs = decoded.inferenceDurationMs,
                timeToFirstTextMs = decoded.timeToFirstTextMs,
                firstTextAudioMs = decoded.firstTextAudioMs,
                realTimeFactor = WhisperEngine.realTimeFactor(
                    decoded.inferenceDurationMs,
                    decoded.audioDurationMs,
                ),
                partialUpdateCount = decoded.partialUpdateCount,
                endpointCount = decoded.endpointCount,
                decodePassCount = decoded.decodePassCount,
                peakPssKb = performance.peakPssKb,
                maximumThermalStatus = performance.maximumThermalStatus,
                maximumBatteryTemperatureC = performance.maximumBatteryTemperatureC,
                transcript = decoded.transcript,
            ).also { result ->
                withContext(Dispatchers.IO) {
                    persistResult(
                        sessionDirectory = sessionDirectory,
                        descriptor = descriptor,
                        result = result,
                        finalizedTexts = decoded.finalizedTexts,
                    )
                }
            }
        } finally {
            samplerJob.cancel()
        }
    }

    private fun loadInput(sessionDirectory: File): StreamingLabInput {
        val checkpointJson = artifactStore.readText(File(sessionDirectory, "checkpoint.json"))
        val capturePipelineId = Regex("\\\"capturePipeline\\\":\\\"([^\\\"]+)\\\"")
            .find(checkpointJson)?.groupValues?.get(1) ?: "direct-16k"
        val pcmFiles = sessionDirectory.listFiles { file ->
            file.isFile && file.name.matches(Regex("segment-\\d{4}\\.pcm"))
        }.orEmpty().sortedBy { it.name }
        require(pcmFiles.isNotEmpty()) { "No PCM segments available" }
        val totalBytes = pcmFiles.sumOf(artifactStore::plaintextSize)
        require(totalBytes <= Int.MAX_VALUE) { "Lab session is too large to load" }
        val pcm = ByteArray(totalBytes.toInt())
        var offset = 0
        pcmFiles.forEach { file ->
            artifactStore.openInput(file).buffered().use { input ->
                while (offset < pcm.size) {
                    val read = input.read(pcm, offset, pcm.size - offset)
                    if (read < 0) break
                    offset += read
                }
            }
        }
        require(offset == pcm.size) { "Unable to read complete PCM input" }
        return StreamingLabInput(pcm, capturePipelineId)
    }

    private fun persistResult(
        sessionDirectory: File,
        descriptor: SherpaStreamingModelDescriptor,
        result: SherpaStreamingLabResult,
        finalizedTexts: List<String>,
    ) {
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("backend", "sherpa-onnx-1.13.4")
            put("modelId", result.modelId)
            put("modelLicense", descriptor.license)
            put("modelSourceRevision", descriptor.sourceRevision)
            put("capturePipelineId", result.capturePipelineId)
            put("benchmarkConfigId", result.benchmarkConfigId)
            put("threadCount", result.threadCount)
            put("frameMs", result.frameMs)
            put("audioDurationMs", result.audioDurationMs)
            put("inferenceDurationMs", result.inferenceDurationMs)
            put("timeToFirstTextMs", result.timeToFirstTextMs ?: JSONObject.NULL)
            put("firstTextAudioMs", result.firstTextAudioMs ?: JSONObject.NULL)
            put("realTimeFactor", result.realTimeFactor)
            put("partialUpdateCount", result.partialUpdateCount)
            put("endpointCount", result.endpointCount)
            put("decodePassCount", result.decodePassCount)
            put("peakPssKb", result.peakPssKb)
            put("maximumThermalStatus", result.maximumThermalStatus)
            put(
                "maximumBatteryTemperatureC",
                result.maximumBatteryTemperatureC ?: JSONObject.NULL,
            )
            put("transcript", result.transcript)
            put("finalizedTexts", JSONArray(finalizedTexts))
        }
        val output = File(
            sessionDirectory,
            "asr-result-${descriptor.id}-${result.benchmarkConfigId}.json",
        )
        artifactStore.writeTextAtomically(output, json.toString())
    }

    private data class StreamingLabInput(
        val pcm: ByteArray,
        val capturePipelineId: String,
    )
}
