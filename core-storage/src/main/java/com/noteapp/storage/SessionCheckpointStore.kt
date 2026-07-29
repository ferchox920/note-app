package com.noteapp.storage

import com.noteapp.domain.RecordingSession
import com.noteapp.domain.SessionStatus
import com.noteapp.security.SessionArtifactStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface SessionCheckpointStore {
    suspend fun findRecoverable(): List<RecordingSession>
    suspend fun findCompleted(): List<RecordingSession>
}

data class PersistedTranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class SessionArtifactSnapshot(
    val directory: File,
    val checkpointUpdatedAtEpochMs: Long,
    val session: RecordingSession,
    val transcriptModelId: String?,
    val transcriptSegments: List<PersistedTranscriptSegment>,
    val transcriptMetrics: List<PersistedProcessingMetric>,
)

private data class PersistedTranscriptArtifact(
    val modelId: String,
    val segments: List<PersistedTranscriptSegment>,
    val metrics: List<PersistedProcessingMetric>,
)

class FileSessionArtifactReader(
    private val recordingsDirectory: File,
    private val artifactStore: SessionArtifactStore,
) {
    fun readAll(): List<SessionArtifactSnapshot> =
        recordingsDirectory.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull(::read)
            .sortedByDescending(SessionArtifactSnapshot::checkpointUpdatedAtEpochMs)

    private fun read(directory: File): SessionArtifactSnapshot? {
        return try {
            val checkpoint = File(directory, CHECKPOINT_FILE_NAME)
            if (!checkpoint.isFile) return null
            val json = JSONObject(artifactStore.readText(checkpoint))
            val id = json.getString("sessionId")
            if (id != directory.name) return null
            val status = enumValueOf<SessionStatus>(json.getString("status"))
            val transcript = readTranscript(directory)
            SessionArtifactSnapshot(
                directory = directory,
                checkpointUpdatedAtEpochMs = checkpoint.lastModified(),
                session = RecordingSession(
                    id = id,
                    status = status,
                    durationMs = json.getLong("durationMs"),
                    errorCode = json.optionalString("errorCode"),
                ),
                transcriptModelId = transcript?.modelId,
                transcriptSegments = transcript?.segments.orEmpty(),
                transcriptMetrics = transcript?.metrics.orEmpty(),
            )
        } catch (failure: SecurityException) {
            throw failure
        } catch (_: Exception) {
            null
        }
    }

    private fun readTranscript(
        directory: File,
    ): PersistedTranscriptArtifact? {
        return try {
            val file = File(directory, TRANSCRIPT_FILE_NAME)
            if (!file.isFile) return null
            val json = JSONObject(artifactStore.readText(file))
            val items = json.optJSONArray("segments") ?: JSONArray()
            val segments = List(items.length()) { index ->
                val item = items.getJSONObject(index)
                PersistedTranscriptSegment(
                    startMs = item.getLong("startMs"),
                    endMs = item.getLong("endMs"),
                    text = item.getString("text"),
                )
            }
            val modelId = json.optString("modelId", "unknown")
            val runtime = if (modelId.startsWith("sherpa", ignoreCase = true)) {
                "sherpa-onnx"
            } else {
                "whisper.cpp"
            }
            PersistedTranscriptArtifact(
                modelId = modelId,
                segments = segments,
                metrics = buildList {
                    json.optionalLong("timeToFirstTextMs")?.let {
                        addSummaryMetric("TIME_TO_FIRST_TEXT_MS", it.toDouble(), "ms", runtime)
                    }
                    json.optionalLong("lastVisibleLatencyMs")?.let {
                        addSummaryMetric("LAST_VISIBLE_LATENCY_MS", it.toDouble(), "ms", runtime)
                    }
                    json.optionalDouble("lastRealTimeFactor")?.let {
                        addSummaryMetric("LAST_REAL_TIME_FACTOR", it, "ratio", runtime)
                    }
                    json.optionalLong("partialCount")?.let {
                        addSummaryMetric("PARTIAL_COUNT", it.toDouble(), "count", runtime)
                    }
                    json.optionalLong("droppedPartialCount")?.let {
                        addSummaryMetric("DROPPED_PARTIAL_COUNT", it.toDouble(), "count", runtime)
                    }
                    json.optionalLong("stableConflictCount")?.let {
                        addSummaryMetric("STABLE_CONFLICT_COUNT", it.toDouble(), "count", runtime)
                    }
                    json.optionalLong("suppressedRepetitionCount")?.let {
                        addSummaryMetric(
                            "SUPPRESSED_REPETITION_COUNT",
                            it.toDouble(),
                            "count",
                            runtime,
                        )
                    }
                },
            )
        } catch (failure: SecurityException) {
            throw failure
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private fun JSONObject.optionalLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else getLong(name)

    private fun JSONObject.optionalDouble(name: String): Double? =
        if (!has(name) || isNull(name)) null else getDouble(name)

    private fun MutableList<PersistedProcessingMetric>.addSummaryMetric(
        name: String,
        value: Double,
        unit: String,
        runtime: String,
    ) {
        add(
            PersistedProcessingMetric(
                name = name,
                value = value,
                unit = unit,
                phase = "incremental_summary",
                runtime = runtime,
                delegate = "cpu",
            ),
        )
    }

    private companion object {
        const val CHECKPOINT_FILE_NAME = "checkpoint.json"
        const val TRANSCRIPT_FILE_NAME = "incremental-transcript.json"
    }
}

class FileSessionCheckpointStore(
    private val recordingsDirectory: File,
    private val artifactStore: SessionArtifactStore,
) : SessionCheckpointStore {
    override suspend fun findRecoverable(): List<RecordingSession> =
        FileSessionArtifactReader(recordingsDirectory, artifactStore).readAll()
            .filter { it.session.status in RECOVERABLE_STATUSES }
            .map { it.session.copy(status = SessionStatus.RECOVERING) }

    override suspend fun findCompleted(): List<RecordingSession> =
        FileSessionArtifactReader(recordingsDirectory, artifactStore).readAll()
            .filter { it.session.status == SessionStatus.COMPLETED }
            .map(SessionArtifactSnapshot::session)

    private companion object {
        val RECOVERABLE_STATUSES = setOf(
            SessionStatus.RECORDING,
            SessionStatus.PAUSED,
            SessionStatus.RECOVERING,
        )
    }
}
