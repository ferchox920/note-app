package com.noteapp.vad

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class VadEngineComparison(
    val engine: String,
    val capturePipelineId: String,
    val segmentCount: Int,
    val processedDurationMs: Long,
    val detectedSpeechDurationMs: Long,
    val speechCoverage: Double,
    val processingDurationMs: Long,
    val realTimeFactor: Double,
)

data class VadComparisonResult(val engines: List<VadEngineComparison>)

class VadComparisonRunner(context: Context) {
    private val applicationContext = context.applicationContext
    private val timelineStore = VadTimelineStore()

    suspend fun compare(sessionDirectory: File): VadComparisonResult = withContext(Dispatchers.IO) {
        val pcmFiles = sessionDirectory.listFiles { file ->
            file.isFile && file.name.matches(Regex("segment-\\d{4}\\.pcm"))
        }.orEmpty().sortedBy { it.name }
        require(pcmFiles.isNotEmpty()) { "No PCM segments available" }
        val checkpoint = File(sessionDirectory, "checkpoint.json").readText(Charsets.UTF_8)
        val capturePipelineId = Regex("\\\"capturePipeline\\\":\\\"([^\\\"]+)\\\"")
            .find(checkpoint)?.groupValues?.get(1) ?: "direct-16k"

        val results = listOf(
            runDetector(
                sessionDirectory,
                pcmFiles,
                engine = "webrtc-vad",
                mode = "AGGRESSIVE",
                detector = WebRtcFrameVoiceDetector(),
                capturePipelineId = capturePipelineId,
            ),
            runDetector(
                sessionDirectory,
                pcmFiles,
                engine = "silero-vad",
                mode = "NORMAL",
                detector = SileroFrameVoiceDetector(applicationContext),
                capturePipelineId = capturePipelineId,
            ),
        )
        persistSummary(sessionDirectory, results)
        VadComparisonResult(results)
    }

    private fun runDetector(
        sessionDirectory: File,
        pcmFiles: List<File>,
        engine: String,
        mode: String,
        detector: FrameVoiceDetector,
        capturePipelineId: String,
    ): VadEngineComparison {
        val frameSize = detector.frameSizeSamples
        val frameDurationMs = frameSize * 1_000 / detector.sampleRateHz
        val segmenter = VadSegmenter(detector)
        val segments = mutableListOf<VadSpeechSegment>()
        val buffer = ByteArray(32_000)
        val started = System.nanoTime()
        try {
            pcmFiles.forEach { file ->
                file.inputStream().buffered().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) segments += segmenter.process(buffer, 0, read).closedSegments
                    }
                }
                segments += segmenter.endCurrentStream().closedSegments
            }
            val processingDurationMs = (System.nanoTime() - started) / 1_000_000L
            val processedDurationMs = segmenter.processedDurationMs
            timelineStore.write(
                sessionDirectory = sessionDirectory,
                sessionId = sessionDirectory.name,
                segments = segments,
                processedDurationMs = processedDurationMs,
                fileName = "vad-comparison-$engine.json",
                engine = engine,
                frameSizeSamples = frameSize,
                frameDurationMs = frameDurationMs,
                mode = mode,
                capturePipelineId = capturePipelineId,
            )
            val speechDurationMs = segments.sumOf { it.endMs - it.startMs }
            return VadEngineComparison(
                engine = engine,
                capturePipelineId = capturePipelineId,
                segmentCount = segments.size,
                processedDurationMs = processedDurationMs,
                detectedSpeechDurationMs = speechDurationMs,
                speechCoverage = if (processedDurationMs == 0L) 0.0
                    else speechDurationMs.toDouble() / processedDurationMs,
                processingDurationMs = processingDurationMs,
                realTimeFactor = if (processedDurationMs == 0L) 0.0
                    else processingDurationMs.toDouble() / processedDurationMs,
            )
        } finally {
            segmenter.close()
        }
    }

    private fun persistSummary(sessionDirectory: File, results: List<VadEngineComparison>) {
        val enginesJson = results.joinToString(",") { result ->
            """{"engine":"${result.engine}","capturePipelineId":"${result.capturePipelineId}","segmentCount":${result.segmentCount},"processedDurationMs":${result.processedDurationMs},"detectedSpeechDurationMs":${result.detectedSpeechDurationMs},"speechCoverage":${decimal(result.speechCoverage)},"processingDurationMs":${result.processingDurationMs},"realTimeFactor":${decimal(result.realTimeFactor)}}"""
        }
        File(sessionDirectory, "vad-comparison.json").writeText(
            """{"schemaVersion":1,"engines":[$enginesJson]}""",
            Charsets.UTF_8,
        )
    }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.8f", value)
}
