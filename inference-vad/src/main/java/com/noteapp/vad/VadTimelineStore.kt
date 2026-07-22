package com.noteapp.vad

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class VadTimeline(
    val processedDurationMs: Long,
    val segments: List<VadSpeechSegment>,
)

class VadTimelineStore {
    fun read(sessionDirectory: File): VadTimeline {
        val file = File(sessionDirectory, FILE_NAME)
        if (!file.isFile) return VadTimeline(0, emptyList())
        val json = file.readText(Charsets.UTF_8)
        val processedDurationMs = numericField(json, "processedDurationMs")
        val body = Regex("\\\"segments\\\":\\[(.*)]}").find(json)?.groupValues?.get(1).orEmpty()
        val segments = if (body.isBlank()) emptyList() else Regex("\\{[^{}]+}")
            .findAll(body)
            .map { match ->
                val segment = match.value
                VadSpeechSegment(
                    sequence = numericField(segment, "sequence").toInt(),
                    startMs = numericField(segment, "startMs"),
                    endMs = numericField(segment, "endMs"),
                    startByteOffset = numericField(segment, "startByteOffset"),
                    endByteOffset = numericField(segment, "endByteOffset"),
                )
            }
            .toList()
        return VadTimeline(processedDurationMs, segments)
    }

    fun write(
        sessionDirectory: File,
        sessionId: String,
        segments: List<VadSpeechSegment>,
        processedDurationMs: Long,
        fileName: String = FILE_NAME,
        engine: String = "webrtc-vad",
        frameSizeSamples: Int = 320,
        frameDurationMs: Int = 20,
        mode: String = "AGGRESSIVE",
        capturePipelineId: String = "unknown",
    ) {
        val segmentJson = segments.joinToString(",") { segment ->
            """{"sequence":${segment.sequence},"startMs":${segment.startMs},"endMs":${segment.endMs},"startByteOffset":${segment.startByteOffset},"endByteOffset":${segment.endByteOffset}}"""
        }
        val json = """{"schemaVersion":1,"sessionId":"$sessionId","engine":"$engine","capturePipelineId":"$capturePipelineId","sampleRateHz":16000,"frameSizeSamples":$frameSizeSamples,"frameDurationMs":$frameDurationMs,"mode":"$mode","minimumSpeechMs":60,"hangoverMs":300,"preRollMs":200,"processedDurationMs":$processedDurationMs,"segments":[$segmentJson]}"""
        val target = File(sessionDirectory, fileName)
        val temporary = File(sessionDirectory, "$fileName.tmp")
        temporary.writeText(json, Charsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val FILE_NAME = "vad-segments.json"

        private fun numericField(json: String, name: String): Long =
            requireNotNull(Regex("\\\"${Regex.escape(name)}\\\":(-?\\d+)").find(json)) {
                "Missing numeric field $name"
            }.groupValues[1].toLong()
    }
}
