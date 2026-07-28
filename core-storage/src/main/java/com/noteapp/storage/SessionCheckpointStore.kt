package com.noteapp.storage

import com.noteapp.domain.RecordingSession

interface SessionCheckpointStore {
    suspend fun findRecoverable(): List<RecordingSession>
    suspend fun findCompleted(): List<RecordingSession>
}

class FileSessionCheckpointStore(private val recordingsDirectory: java.io.File) : SessionCheckpointStore {
    override suspend fun findRecoverable(): List<RecordingSession> =
        recordingsDirectory.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull(::readRecoverable)
            .sortedByDescending { it.id }

    override suspend fun findCompleted(): List<RecordingSession> =
        recordingsDirectory.listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedByDescending { directory ->
                java.io.File(directory, "checkpoint.json").lastModified()
            }
            .mapNotNull(::readCompleted)

    private fun readRecoverable(directory: java.io.File): RecordingSession? = runCatching {
        val checkpoint = java.io.File(directory, "checkpoint.json")
        if (!checkpoint.isFile) return@runCatching null
        val json = checkpoint.readText(Charsets.UTF_8)
        val id = stringField(json, "sessionId") ?: return@runCatching null
        if (id != directory.name) return@runCatching null
        val status = enumValueOf<com.noteapp.domain.SessionStatus>(
            stringField(json, "status") ?: return@runCatching null,
        )
        if (status !in RECOVERABLE_STATUSES) return@runCatching null
        RecordingSession(
            id = id,
            status = com.noteapp.domain.SessionStatus.RECOVERING,
            durationMs = numericField(json, "durationMs"),
            errorCode = stringField(json, "errorCode"),
        )
    }.getOrNull()

    private fun readCompleted(directory: java.io.File): RecordingSession? = runCatching {
        val checkpoint = java.io.File(directory, "checkpoint.json")
        if (!checkpoint.isFile) return@runCatching null
        val json = checkpoint.readText(Charsets.UTF_8)
        val id = stringField(json, "sessionId") ?: return@runCatching null
        if (id != directory.name) return@runCatching null
        val status = enumValueOf<com.noteapp.domain.SessionStatus>(
            stringField(json, "status") ?: return@runCatching null,
        )
        if (status != com.noteapp.domain.SessionStatus.COMPLETED) return@runCatching null
        RecordingSession(
            id = id,
            status = status,
            durationMs = numericField(json, "durationMs"),
            errorCode = null,
        )
    }.getOrNull()

    private fun stringField(json: String, name: String): String? =
        Regex("\\\"${Regex.escape(name)}\\\":\\\"([^\\\"]*)\\\"").find(json)?.groupValues?.get(1)

    private fun numericField(json: String, name: String): Long =
        requireNotNull(Regex("\\\"${Regex.escape(name)}\\\":(-?\\d+)").find(json)) {
            "Missing numeric field $name"
        }.groupValues[1].toLong()

    private companion object {
        val RECOVERABLE_STATUSES = setOf(
            com.noteapp.domain.SessionStatus.RECORDING,
            com.noteapp.domain.SessionStatus.PAUSED,
            com.noteapp.domain.SessionStatus.RECOVERING,
        )
    }
}
