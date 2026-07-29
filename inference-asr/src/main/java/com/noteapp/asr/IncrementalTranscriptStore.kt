package com.noteapp.asr

import kotlinx.collections.immutable.toPersistentList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
        val metrics = if (json.optString("metricsJournalFile") == JOURNAL_FILE_NAME) {
            readJournal(
                sessionDirectory = sessionDirectory,
                expectedCount = json.getInt("metricCount"),
            )
        } else {
            Files.deleteIfExists(File(sessionDirectory, JOURNAL_FILE_NAME).toPath())
            val jsonMetrics = json.optJSONArray("inferenceMetrics") ?: JSONArray()
            List(jsonMetrics.length()) { index ->
                metricFromJson(jsonMetrics.getJSONObject(index))
            }
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
            suppressedRepetitionCount = json.optInt("suppressedRepetitionCount"),
            timeToFirstTextMs = json.optionalLong("timeToFirstTextMs"),
            lastVisibleLatencyMs = json.optionalLong("lastVisibleLatencyMs"),
            lastRealTimeFactor = json.optionalDouble("lastRealTimeFactor"),
            finalizedSegments = segments,
            inferenceMetrics = metrics.toPersistentList(),
            errorCode = json.optionalString("errorCode"),
        )
    }

    fun persistedMetricCount(sessionDirectory: File): Int {
        val file = File(sessionDirectory, FILE_NAME)
        if (!file.isFile) return 0
        val json = JSONObject(file.readText(Charsets.UTF_8))
        return if (json.optString("metricsJournalFile") == JOURNAL_FILE_NAME) {
            json.optInt("metricCount", 0)
        } else {
            0
        }
    }

    fun writeCheckpoint(
        sessionDirectory: File,
        modelId: String,
        capturePipelineId: String,
        state: IncrementalAsrState,
        persistedMetricCount: Int,
    ): Int {
        require(persistedMetricCount in 0..state.inferenceMetrics.size) {
            "Persisted incremental metric count is outside the state timeline"
        }
        val journal = File(sessionDirectory, JOURNAL_FILE_NAME)
        if (persistedMetricCount == 0 && journal.isFile) {
            RandomAccessFile(journal, "rw").use { it.setLength(0) }
        }
        val journalExisted = journal.isFile
        val journalLength = if (journalExisted) journal.length() else 0L
        return try {
            appendMetrics(
                sessionDirectory,
                state.inferenceMetrics.subList(persistedMetricCount, state.inferenceMetrics.size),
            )
            val updatedMetricCount = state.inferenceMetrics.size
            writeJson(
                sessionDirectory = sessionDirectory,
                modelId = modelId,
                capturePipelineId = capturePipelineId,
                state = state,
                includeMetrics = false,
                journalMetricCount = updatedMetricCount,
            )
            updatedMetricCount
        } catch (failure: Exception) {
            if (journalExisted) {
                RandomAccessFile(journal, "rw").use { it.setLength(journalLength) }
            } else {
                journal.delete()
            }
            throw failure
        }
    }

    fun writeFinal(
        sessionDirectory: File,
        modelId: String,
        capturePipelineId: String,
        state: IncrementalAsrState,
        persistedMetricCount: Int,
    ) {
        writeCheckpoint(
            sessionDirectory = sessionDirectory,
            modelId = modelId,
            capturePipelineId = capturePipelineId,
            state = state,
            persistedMetricCount = persistedMetricCount,
        )
        writeJson(
            sessionDirectory = sessionDirectory,
            modelId = modelId,
            capturePipelineId = capturePipelineId,
            state = state,
            includeMetrics = true,
            journalMetricCount = null,
        )
        Files.deleteIfExists(File(sessionDirectory, JOURNAL_FILE_NAME).toPath())
    }

    /** Writes a self-contained final artifact for lab runners and compatibility. */
    fun write(
        sessionDirectory: File,
        modelId: String,
        capturePipelineId: String,
        state: IncrementalAsrState,
    ) = writeJson(
        sessionDirectory = sessionDirectory,
        modelId = modelId,
        capturePipelineId = capturePipelineId,
        state = state,
        includeMetrics = true,
        journalMetricCount = null,
    )

    private fun writeJson(
        sessionDirectory: File,
        modelId: String,
        capturePipelineId: String,
        state: IncrementalAsrState,
        includeMetrics: Boolean,
        journalMetricCount: Int?,
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
        val metrics = if (includeMetrics) {
            JSONArray().apply {
                state.inferenceMetrics.forEach { metric -> put(metricToJson(metric)) }
            }
        } else {
            null
        }
        val json = JSONObject().apply {
            put("schemaVersion", 5)
            put("modelId", modelId)
            put("capturePipelineId", capturePipelineId)
            put("language", "es")
            put("stableText", state.stableText)
            put("unstableText", state.unstableText)
            put("partialCount", state.partialCount)
            put("droppedPartialCount", state.droppedPartialCount)
            put("stableConflictCount", state.stableConflictCount)
            put("suppressedRepetitionCount", state.suppressedRepetitionCount)
            put("timeToFirstTextMs", state.timeToFirstTextMs ?: JSONObject.NULL)
            put("lastVisibleLatencyMs", state.lastVisibleLatencyMs ?: JSONObject.NULL)
            put("lastRealTimeFactor", state.lastRealTimeFactor ?: JSONObject.NULL)
            put("errorCode", state.errorCode ?: JSONObject.NULL)
            put("segments", segments)
            if (metrics != null) {
                put("inferenceMetrics", metrics)
            } else {
                put("metricsJournalFile", JOURNAL_FILE_NAME)
                put("metricCount", requireNotNull(journalMetricCount))
            }
        }
        val target = File(sessionDirectory, FILE_NAME)
        atomicWriteText(target, json.toString())
    }

    private fun atomicWriteText(target: File, content: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
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

    private fun readJournal(
        sessionDirectory: File,
        expectedCount: Int,
    ): List<IncrementalInferenceMetric> {
        require(expectedCount >= 0) { "Incremental metric count must not be negative" }
        val journal = File(sessionDirectory, JOURNAL_FILE_NAME)
        if (expectedCount == 0) {
            Files.deleteIfExists(journal.toPath())
            return emptyList()
        }
        require(journal.isFile) { "Missing incremental metric journal" }
        val retainedLines = journal.useLines { lines ->
            lines.take(expectedCount + 1).toList()
        }
        require(retainedLines.size >= expectedCount) { "Incremental metric journal is truncated" }
        if (retainedLines.size > expectedCount) {
            atomicWriteText(
                journal,
                retainedLines.take(expectedCount).joinToString(separator = "\n", postfix = "\n"),
            )
        }
        val metrics = retainedLines.take(expectedCount).map { line ->
            metricFromJson(JSONObject(line))
        }
        metrics.forEachIndexed { index, metric ->
            require(metric.sequence == index) { "Incremental metric journal sequence is not contiguous" }
        }
        return metrics
    }

    private fun appendMetrics(
        sessionDirectory: File,
        metrics: List<IncrementalInferenceMetric>,
    ) {
        if (metrics.isEmpty()) return
        val journal = File(sessionDirectory, JOURNAL_FILE_NAME)
        FileOutputStream(journal, true).use { output ->
            val writer = output.bufferedWriter(Charsets.UTF_8)
            metrics.forEach { metric ->
                writer.append(metricToJson(metric).toString())
                writer.newLine()
            }
            writer.flush()
            output.fd.sync()
        }
    }

    private fun metricToJson(metric: IncrementalInferenceMetric): JSONObject =
        JSONObject().apply {
            put("sequence", metric.sequence)
            put("windowStartMs", metric.windowStartMs)
            put("windowEndMs", metric.windowEndMs)
            put("final", metric.final)
            put("audioDurationMs", metric.audioDurationMs)
            put("inferenceDurationMs", metric.inferenceDurationMs)
            put("visibleLatencyMs", metric.visibleLatencyMs)
            put("realTimeFactor", metric.realTimeFactor)
            put("reusedResult", metric.reusedResult)
            put("suppressedRepetition", metric.suppressedRepetition)
            put("nativeTimings", JSONObject().apply {
                put("sampleMs", metric.nativeTimings.sampleMs.toDouble())
                put("encodeMs", metric.nativeTimings.encodeMs.toDouble())
                put("decodeMs", metric.nativeTimings.decodeMs.toDouble())
                put("batchMs", metric.nativeTimings.batchMs.toDouble())
                put("promptMs", metric.nativeTimings.promptMs.toDouble())
            })
        }

    private fun metricFromJson(item: JSONObject): IncrementalInferenceMetric =
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
            suppressedRepetition = item.optBoolean("suppressedRepetition", false),
            nativeTimings = item.optJSONObject("nativeTimings")?.let { timings ->
                WhisperNativeTimings(
                    sampleMs = timings.optDouble("sampleMs").toFloat(),
                    encodeMs = timings.optDouble("encodeMs").toFloat(),
                    decodeMs = timings.optDouble("decodeMs").toFloat(),
                    batchMs = timings.optDouble("batchMs").toFloat(),
                    promptMs = timings.optDouble("promptMs").toFloat(),
                )
            } ?: WhisperNativeTimings(0f, 0f, 0f, 0f, 0f),
        )

    companion object {
        const val FILE_NAME = "incremental-transcript.json"
        const val JOURNAL_FILE_NAME = "incremental-metrics.jsonl"
    }

    private fun JSONObject.optionalLong(name: String): Long? =
        if (has(name) && !isNull(name)) getLong(name) else null

    private fun JSONObject.optionalDouble(name: String): Double? =
        if (has(name) && !isNull(name)) getDouble(name) else null

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
