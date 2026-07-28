package com.noteapp.asr

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class IncrementalTranscriptStore {
    fun read(sessionDirectory: File): IncrementalAsrState? {
        val file = File(sessionDirectory, FILE_NAME)
        if (!file.isFile) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val jsonSegments = json.optJSONArray("segments") ?: JSONArray()
        val segments = List(jsonSegments.length()) { index ->
            val item = jsonSegments.getJSONObject(index)
            IncrementalTranscriptSegment(
                startMs = item.getLong("startMs"),
                endMs = item.getLong("endMs"),
                text = item.getString("text"),
            )
        }
        val jsonMetrics = json.optJSONArray("inferenceMetrics") ?: JSONArray()
        val metrics = List(jsonMetrics.length()) { index ->
            val item = jsonMetrics.getJSONObject(index)
            IncrementalInferenceMetric(
                sequence = item.getInt("sequence"),
                windowStartMs = item.getLong("windowStartMs"),
                windowEndMs = item.getLong("windowEndMs"),
                final = item.getBoolean("final"),
                audioDurationMs = item.getLong("audioDurationMs"),
                inferenceDurationMs = item.getLong("inferenceDurationMs"),
                visibleLatencyMs = item.getLong("visibleLatencyMs"),
                realTimeFactor = item.getDouble("realTimeFactor"),
                reusedResult = item.optBoolean("reusedResult", false),
            )
        }
        return IncrementalAsrState(
            enabled = true,
            // An interrupted active hypothesis is intentionally discarded; only
            // endpoint-finalized segments are safe to restore as immutable text.
            stableText = segments.joinToString(" ") { it.text }.trim(),
            unstableText = "",
            droppedPartialCount = json.optLong("droppedPartialCount"),
            partialCount = json.optInt("partialCount"),
            stableConflictCount = json.optInt("stableConflictCount"),
            timeToFirstTextMs = json.optionalLong("timeToFirstTextMs"),
            lastVisibleLatencyMs = json.optionalLong("lastVisibleLatencyMs"),
            lastRealTimeFactor = json.optionalDouble("lastRealTimeFactor"),
            finalizedSegments = segments,
            inferenceMetrics = metrics,
            errorCode = json.optionalString("errorCode"),
        )
    }

    fun write(
        sessionDirectory: File,
        modelId: String,
        capturePipelineId: String,
        state: IncrementalAsrState,
    ) {
        val segments = JSONArray().apply {
            state.finalizedSegments.forEach { segment ->
                put(JSONObject().apply {
                    put("startMs", segment.startMs)
                    put("endMs", segment.endMs)
                    put("text", segment.text)
                })
            }
        }
        val metrics = JSONArray().apply {
            state.inferenceMetrics.forEach { metric ->
                put(JSONObject().apply {
                    put("sequence", metric.sequence)
                    put("windowStartMs", metric.windowStartMs)
                    put("windowEndMs", metric.windowEndMs)
                    put("final", metric.final)
                    put("audioDurationMs", metric.audioDurationMs)
                    put("inferenceDurationMs", metric.inferenceDurationMs)
                    put("visibleLatencyMs", metric.visibleLatencyMs)
                    put("realTimeFactor", metric.realTimeFactor)
                    put("reusedResult", metric.reusedResult)
                })
            }
        }
        val json = JSONObject().apply {
            put("schemaVersion", 2)
            put("modelId", modelId)
            put("capturePipelineId", capturePipelineId)
            put("language", "es")
            put("stableText", state.stableText)
            put("unstableText", state.unstableText)
            put("partialCount", state.partialCount)
            put("droppedPartialCount", state.droppedPartialCount)
            put("stableConflictCount", state.stableConflictCount)
            put("timeToFirstTextMs", state.timeToFirstTextMs ?: JSONObject.NULL)
            put("lastVisibleLatencyMs", state.lastVisibleLatencyMs ?: JSONObject.NULL)
            put("lastRealTimeFactor", state.lastRealTimeFactor ?: JSONObject.NULL)
            put("errorCode", state.errorCode ?: JSONObject.NULL)
            put("segments", segments)
            put("inferenceMetrics", metrics)
        }
        val target = File(sessionDirectory, FILE_NAME)
        val temporary = File(sessionDirectory, "$FILE_NAME.tmp")
        temporary.writeText(json.toString(), Charsets.UTF_8)
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
        const val FILE_NAME = "incremental-transcript.json"
    }

    private fun JSONObject.optionalLong(name: String): Long? =
        if (has(name) && !isNull(name)) getLong(name) else null

    private fun JSONObject.optionalDouble(name: String): Double? =
        if (has(name) && !isNull(name)) getDouble(name) else null

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
