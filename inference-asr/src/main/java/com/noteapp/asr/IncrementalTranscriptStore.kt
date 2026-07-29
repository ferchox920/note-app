package com.noteapp.asr

import com.noteapp.security.SessionArtifactStore
import kotlinx.collections.immutable.toPersistentList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class IncrementalTranscriptDocument(
    val modelId: String,
    val capturePipelineId: String,
    val state: IncrementalAsrState,
)

class IncrementalTranscriptStore(
    private val artifactStore: SessionArtifactStore,
) {
    fun read(sessionDirectory: File): IncrementalAsrState? =
        readDocument(sessionDirectory)?.state

    fun readDocument(sessionDirectory: File): IncrementalTranscriptDocument? {
        val file = File(sessionDirectory, FILE_NAME)
        if (!file.isFile) return null
        val json = JSONObject(artifactStore.readText(file))
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
            artifactStore.delete(File(sessionDirectory, JOURNAL_FILE_NAME))
            val jsonMetrics = json.optJSONArray("inferenceMetrics") ?: JSONArray()
            List(jsonMetrics.length()) { index ->
                metricFromJson(jsonMetrics.getJSONObject(index))
            }
        }
        return IncrementalTranscriptDocument(
            modelId = json.getString("modelId"),
            capturePipelineId = json.optString("capturePipelineId", "unknown"),
            state = IncrementalAsrState(
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
            ),
        )
    }

    fun persistedMetricCount(sessionDirectory: File): Int {
        val file = File(sessionDirectory, FILE_NAME)
        if (!file.isFile) return 0
        val json = JSONObject(artifactStore.readText(file))
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
            check(artifactStore.delete(journal)) { "INCREMENTAL_JOURNAL_DELETE_FAILED" }
        }
        val previousJournal = journal.takeIf(File::isFile)?.let(artifactStore::readBytes)
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
            if (previousJournal != null) {
                artifactStore.writeBytesAtomically(journal, previousJournal)
            } else {
                artifactStore.delete(journal)
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
        artifactStore.delete(File(sessionDirectory, JOURNAL_FILE_NAME))
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
        artifactStore.writeTextAtomically(target, content)
    }

    private fun readJournal(
        sessionDirectory: File,
        expectedCount: Int,
    ): List<IncrementalInferenceMetric> {
        require(expectedCount >= 0) { "Incremental metric count must not be negative" }
        val journal = File(sessionDirectory, JOURNAL_FILE_NAME)
        if (expectedCount == 0) {
            artifactStore.delete(journal)
            return emptyList()
        }
        require(journal.isFile) { "Missing incremental metric journal" }
        artifactStore.recoverAppend(journal)
        val retainedLines = artifactStore.readText(journal)
            .lineSequence()
            .filter(String::isNotBlank)
            .take(expectedCount + 1)
            .toList()
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
        val content = metrics.joinToString(separator = "\n", postfix = "\n") { metric ->
            metricToJson(metric).toString()
        }
        artifactStore.appendBytes(journal, content.toByteArray(Charsets.UTF_8))
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
