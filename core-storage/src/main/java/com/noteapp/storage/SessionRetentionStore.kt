package com.noteapp.storage

import java.util.concurrent.TimeUnit

data class SessionRetentionResult(
    val deletedSessionIds: List<String>,
)

interface SessionRetentionStore {
    suspend fun apply(retentionDays: Int): SessionRetentionResult
}

/** Applies an explicitly selected retention window to terminal sessions only. */
class RoomSessionRetentionStore(
    private val database: NoteAppDatabase,
    private val deletionStore: SessionDeletionStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : SessionRetentionStore {
    override suspend fun apply(retentionDays: Int): SessionRetentionResult {
        require(retentionDays in AppPreferences.SUPPORTED_RETENTION_DAYS) {
            "INVALID_RETENTION_DAYS"
        }
        if (retentionDays == AppPreferences.RETENTION_FOREVER_DAYS) {
            return SessionRetentionResult(emptyList())
        }

        val retentionMs = TimeUnit.DAYS.toMillis(retentionDays.toLong())
        val cutoffEpochMs = Math.subtractExact(nowEpochMs(), retentionMs)
        val candidates = database.sessionDao().findTerminalEndedBefore(
            statuses = DELETABLE_STATUSES,
            cutoffEpochMs = cutoffEpochMs,
        )
        candidates.forEach { session -> deletionStore.delete(session.id) }
        return SessionRetentionResult(candidates.map(SessionEntity::id))
    }

    private companion object {
        val DELETABLE_STATUSES = listOf("COMPLETED", "FAILED", "ABORTED")
    }
}
