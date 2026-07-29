package com.noteapp.storage

import com.noteapp.domain.RecordingSession
import com.noteapp.domain.SessionStatus
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
)

class FileSessionArtifactReader(private val recordingsDirectory: File) {
    fun readAll(): List<SessionArtifactSnapshot> =
        recordingsDirectory.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull(::read)
            .sortedByDescending(SessionArtifactSnapshot::checkpointUpdatedAtEpochMs)

    private fun read(directory: File): SessionArtifactSnapshot? = runCatching {
        val checkpoint = File(directory, CHECKPOINT_FILE_NAME)
        if (!checkpoint.isFile) return@runCatching null
        val json = JSONObject(checkpoint.readText(Charsets.UTF_8))
        val id = json.getString("sessionId")
        if (id != directory.name) return@runCatching null
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
            transcriptModelId = transcript?.first,
            transcriptSegments = transcript?.second.orEmpty(),
        )
    }.getOrNull()

    private fun readTranscript(
        directory: File,
    ): Pair<String, List<PersistedTranscriptSegment>>? = runCatching {
        val file = File(directory, TRANSCRIPT_FILE_NAME)
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val items = json.optJSONArray("segments") ?: JSONArray()
        val segments = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            PersistedTranscriptSegment(
                startMs = item.getLong("startMs"),
                endMs = item.getLong("endMs"),
                text = item.getString("text"),
            )
        }
        json.optString("modelId", "unknown") to segments
    }.getOrNull()

    private fun JSONObject.optionalString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private companion object {
        const val CHECKPOINT_FILE_NAME = "checkpoint.json"
        const val TRANSCRIPT_FILE_NAME = "incremental-transcript.json"
    }
}

class FileSessionCheckpointStore(private val recordingsDirectory: File) : SessionCheckpointStore {
    override suspend fun findRecoverable(): List<RecordingSession> =
        FileSessionArtifactReader(recordingsDirectory).readAll()
            .filter { it.session.status in RECOVERABLE_STATUSES }
            .map { it.session.copy(status = SessionStatus.RECOVERING) }

    override suspend fun findCompleted(): List<RecordingSession> =
        FileSessionArtifactReader(recordingsDirectory).readAll()
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
