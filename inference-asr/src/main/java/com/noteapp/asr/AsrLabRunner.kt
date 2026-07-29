package com.noteapp.asr

import android.content.Context
import com.noteapp.security.SessionArtifactStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AsrLabResult(
    val modelId: String,
    val capturePipelineId: String,
    val benchmarkConfigId: String,
    val threadCount: Int,
    val maxChunkMs: Long,
    val chunkCount: Int,
    val audioDurationMs: Long,
    val inferenceDurationMs: Long,
    val timeToFirstTextMs: Long?,
    val realTimeFactor: Double,
    val peakPssKb: Int,
    val maximumThermalStatus: Int,
    val maximumBatteryTemperatureC: Double?,
    val nativeSystemInfo: String,
    val nativeTimings: WhisperNativeTimings,
    val transcript: String,
)

class AsrLabRunner(
    context: Context,
    private val artifactStore: SessionArtifactStore,
) {
    private val appContext = context.applicationContext
    private val runMutex = Mutex()

    suspend fun transcribeSession(
        sessionDirectory: File,
        modelFile: File,
        descriptor: WhisperModelDescriptor,
        config: AsrLabConfig = AsrLabConfig.Default,
    ): AsrLabResult {
        check(runMutex.tryLock()) { "ASR_ALREADY_RUNNING" }
        return try {
            transcribeSessionLocked(sessionDirectory, modelFile, descriptor, config)
        } finally {
            runMutex.unlock()
        }
    }

    private suspend fun transcribeSessionLocked(
        sessionDirectory: File,
        modelFile: File,
        descriptor: WhisperModelDescriptor,
        config: AsrLabConfig,
    ): AsrLabResult {
        val input = withContext(Dispatchers.IO) { loadInput(sessionDirectory, config) }
        require(input.chunks.isNotEmpty()) { "No VAD speech segments available" }
        val engine = WhisperEngine.create(
            modelFile = modelFile,
            descriptor = descriptor,
            requestedThreads = config.threadCount,
        )
        val nativeSystemInfo = WhisperEngine.systemInfo()
        val performanceSampler = DevicePerformanceSampler(appContext)
        val samplerJob = performanceSampler.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val labStarted = System.nanoTime()
        var timeToFirstTextMs: Long? = null
        try {
            val transcriptions = input.chunks.map { chunk ->
                val start = chunk.startByteOffset.toInt()
                val end = chunk.endByteOffset.toInt()
                require(start >= 0 && end <= input.pcm.size && start < end) {
                    "VAD byte range is outside recorded PCM"
                }
                engine.transcribePcm16(
                    pcm = input.pcm.copyOfRange(start, end),
                    offsetMs = chunk.startMs,
                    language = "es",
                ).also { transcription ->
                    if (timeToFirstTextMs == null && transcription.segments.any { it.text.isNotBlank() }) {
                        timeToFirstTextMs = (System.nanoTime() - labStarted) / 1_000_000L
                    }
                }
            }
            val transcript = transcriptions
                .flatMap { it.segments }
                .joinToString(" ") { it.text.trim() }
                .trim()
            val audioMs = transcriptions.sumOf { it.audioDurationMs }
            val inferenceMs = transcriptions.sumOf { it.inferenceDurationMs }
            val nativeTimings = WhisperNativeTimings(
                sampleMs = transcriptions.sumOf { it.nativeTimings.sampleMs.toDouble() }.toFloat(),
                encodeMs = transcriptions.sumOf { it.nativeTimings.encodeMs.toDouble() }.toFloat(),
                decodeMs = transcriptions.sumOf { it.nativeTimings.decodeMs.toDouble() }.toFloat(),
                batchMs = transcriptions.sumOf { it.nativeTimings.batchMs.toDouble() }.toFloat(),
                promptMs = transcriptions.sumOf { it.nativeTimings.promptMs.toDouble() }.toFloat(),
            )
            val performance = performanceSampler.finish(samplerJob)
            return AsrLabResult(
                modelId = descriptor.id,
                capturePipelineId = input.capturePipelineId,
                benchmarkConfigId = config.id,
                threadCount = config.threadCount,
                maxChunkMs = config.maxChunkMs,
                chunkCount = input.chunks.size,
                audioDurationMs = audioMs,
                inferenceDurationMs = inferenceMs,
                timeToFirstTextMs = timeToFirstTextMs,
                realTimeFactor = WhisperEngine.realTimeFactor(inferenceMs, audioMs),
                peakPssKb = performance.peakPssKb,
                maximumThermalStatus = performance.maximumThermalStatus,
                maximumBatteryTemperatureC = performance.maximumBatteryTemperatureC,
                nativeSystemInfo = nativeSystemInfo,
                nativeTimings = nativeTimings,
                transcript = transcript,
            ).also { result ->
                withContext(Dispatchers.IO) { persistResult(sessionDirectory, result, transcriptions) }
            }
        } finally {
            samplerJob.cancel()
            engine.release()
        }
    }

    private fun loadInput(
        sessionDirectory: File,
        config: AsrLabConfig,
    ): LabInput {
        val checkpointJson = artifactStore.readText(File(sessionDirectory, "checkpoint.json"))
        val capturePipelineId = Regex("\\\"capturePipeline\\\":\\\"([^\\\"]+)\\\"")
            .find(checkpointJson)?.groupValues?.get(1) ?: "direct-16k"
        val timeline = JSONObject(
            artifactStore.readText(File(sessionDirectory, "vad-segments.json")),
        )
        val jsonSegments = timeline.getJSONArray("segments")
        val intervals = List(jsonSegments.length()) { index ->
            val item = jsonSegments.getJSONObject(index)
            SpeechInterval(
                startMs = item.getLong("startMs"),
                endMs = item.getLong("endMs"),
                startByteOffset = item.getLong("startByteOffset"),
                endByteOffset = item.getLong("endByteOffset"),
            )
        }
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
        return LabInput(pcm, AsrChunkAssembler.assemble(intervals, config), capturePipelineId)
    }

    private fun persistResult(
        sessionDirectory: File,
        result: AsrLabResult,
        transcriptions: List<WhisperTranscription>,
    ) {
        val segments = JSONArray()
        transcriptions.flatMap { it.segments }.forEach { segment ->
            segments.put(JSONObject().apply {
                put("startMs", segment.startMs)
                put("endMs", segment.endMs)
                put("text", segment.text)
                put("noSpeechProbability", segment.noSpeechProbability.toDouble())
            })
        }
        val json = JSONObject().apply {
            put("schemaVersion", 2)
            put("modelId", result.modelId)
            put("capturePipelineId", result.capturePipelineId)
            put("language", "es")
            put("benchmarkConfigId", result.benchmarkConfigId)
            put("threadCount", result.threadCount)
            put("maxChunkMs", result.maxChunkMs)
            put("chunkCount", result.chunkCount)
            put("audioDurationMs", result.audioDurationMs)
            put("inferenceDurationMs", result.inferenceDurationMs)
            put("timeToFirstTextMs", result.timeToFirstTextMs ?: JSONObject.NULL)
            put("realTimeFactor", result.realTimeFactor)
            put("peakPssKb", result.peakPssKb)
            put("maximumThermalStatus", result.maximumThermalStatus)
            put("maximumBatteryTemperatureC", result.maximumBatteryTemperatureC ?: JSONObject.NULL)
            put("nativeSystemInfo", result.nativeSystemInfo)
            put("nativeTimings", JSONObject().apply {
                put("sampleMs", result.nativeTimings.sampleMs.toDouble())
                put("encodeMs", result.nativeTimings.encodeMs.toDouble())
                put("decodeMs", result.nativeTimings.decodeMs.toDouble())
                put("batchMs", result.nativeTimings.batchMs.toDouble())
                put("promptMs", result.nativeTimings.promptMs.toDouble())
            })
            put("transcript", result.transcript)
            put("segments", segments)
        }
        artifactStore.writeTextAtomically(
            File(
                sessionDirectory,
                "asr-result-${result.modelId}-${result.benchmarkConfigId}.json",
            ),
            json.toString(),
        )
    }

    private data class LabInput(
        val pcm: ByteArray,
        val chunks: List<AsrAudioChunk>,
        val capturePipelineId: String,
    )
}
