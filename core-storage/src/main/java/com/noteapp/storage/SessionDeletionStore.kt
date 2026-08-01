package com.noteapp.storage

import androidx.room.withTransaction
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

interface SessionDeletionStore {
    suspend fun recoverInterrupted(): Int

    suspend fun delete(sessionId: String)
}

/**
 * Deletes an inactive session without exposing a partially removed directory as authoritative.
 *
 * The session directory is first atomically renamed to a deterministic tombstone. Room is then
 * deleted transactionally (including its cascades), and only afterwards are the bytes removed.
 * A process death at either boundary leaves the tombstone for [recoverInterrupted] to finish.
 */
class RoomSessionDeletionStore(
    recordingsDirectory: File,
    private val database: NoteAppDatabase,
) : SessionDeletionStore {
    private val root = recordingsDirectory.canonicalFile

    override suspend fun recoverInterrupted(): Int {
        if (!root.exists()) return 0
        check(root.isDirectory) { "RECORDINGS_ROOT_INVALID" }
        val tombstones = root.listFiles()
            .orEmpty()
            .filter { file -> file.name.startsWith(TOMBSTONE_PREFIX) }
            .sortedBy(File::getName)
        tombstones.forEach { tombstone ->
            val sessionId = tombstone.name.removePrefix(TOMBSTONE_PREFIX)
            validateSessionId(sessionId)
            check(isDirectoryWithoutFollowing(tombstone)) { "SESSION_TOMBSTONE_INVALID" }
            val source = sessionDirectory(sessionId)
            check(!source.exists()) { "AMBIGUOUS_SESSION_DELETION_STATE" }
            deleteIndexedSession(sessionId)
            deleteTree(tombstone)
            verifyDeleted(sessionId, source, tombstone)
        }
        return tombstones.size
    }

    override suspend fun delete(sessionId: String) {
        validateSessionId(sessionId)
        ensureRoot()
        val source = sessionDirectory(sessionId)
        val tombstone = tombstoneDirectory(sessionId)
        if (existsWithoutFollowing(tombstone)) {
            check(isDirectoryWithoutFollowing(tombstone)) { "SESSION_TOMBSTONE_INVALID" }
            check(!source.exists()) { "AMBIGUOUS_SESSION_DELETION_STATE" }
            deleteIndexedSession(sessionId)
            deleteTree(tombstone)
            verifyDeleted(sessionId, source, tombstone)
            return
        }

        val indexed = database.sessionDao().findById(sessionId)
        if (source.exists()) {
            checkNotNull(indexed) { "SESSION_NOT_INDEXED" }
            check(indexed.status in DELETABLE_STATUSES) { "ACTIVE_SESSION_DELETE_REFUSED" }
            moveToTombstone(source, tombstone)
        } else if (indexed != null) {
            check(indexed.status in DELETABLE_STATUSES) { "ACTIVE_SESSION_DELETE_REFUSED" }
        }

        deleteIndexedSession(sessionId)
        deleteTree(tombstone)
        verifyDeleted(sessionId, source, tombstone)
    }

    private suspend fun deleteIndexedSession(sessionId: String) {
        database.withTransaction {
            database.sessionDao().deleteById(sessionId)
        }
    }

    private suspend fun verifyDeleted(
        sessionId: String,
        source: File,
        tombstone: File,
    ) {
        check(!source.exists() && !existsWithoutFollowing(tombstone)) {
            "SESSION_FILES_DELETE_INCOMPLETE"
        }
        check(database.sessionDao().findById(sessionId) == null) { "SESSION_ROW_DELETE_INCOMPLETE" }
        check(database.transcriptSegmentDao().findBySession(sessionId).isEmpty()) {
            "SESSION_TRANSCRIPTS_DELETE_INCOMPLETE"
        }
        check(database.noteDao().findBySession(sessionId).isEmpty()) {
            "SESSION_NOTES_DELETE_INCOMPLETE"
        }
        check(database.processingJobDao().findBySession(sessionId).isEmpty()) {
            "SESSION_JOBS_DELETE_INCOMPLETE"
        }
        check(database.sessionMetricDao().findBySession(sessionId).isEmpty()) {
            "SESSION_METRICS_DELETE_INCOMPLETE"
        }
    }

    private fun ensureRoot() {
        check(root.exists() || root.mkdirs()) { "RECORDINGS_ROOT_CREATE_FAILED" }
        check(root.isDirectory) { "RECORDINGS_ROOT_INVALID" }
    }

    private fun sessionDirectory(sessionId: String): File =
        directChild(sessionId)

    private fun tombstoneDirectory(sessionId: String): File =
        directChild("$TOMBSTONE_PREFIX$sessionId")

    private fun directChild(name: String): File = File(root, name).canonicalFile.also { candidate ->
        check(candidate.parentFile == root) { "SESSION_DELETE_PATH_INVALID" }
    }

    private fun moveToTombstone(source: File, tombstone: File) {
        try {
            Files.move(source.toPath(), tombstone.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IllegalStateException("SESSION_DELETE_ATOMIC_MOVE_UNSUPPORTED", failure)
        }
    }

    private fun deleteTree(directory: File) {
        if (!existsWithoutFollowing(directory)) return
        Files.walkFileTree(
            directory.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    failure: java.io.IOException?,
                ): FileVisitResult {
                    failure?.let { throw it }
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        check(!existsWithoutFollowing(directory)) { "SESSION_TOMBSTONE_DELETE_FAILED" }
    }

    private fun existsWithoutFollowing(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun isDirectoryWithoutFollowing(file: File): Boolean =
        Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun validateSessionId(sessionId: String) {
        require(sessionId.matches(SAFE_SESSION_ID)) { "SESSION_ID_INVALID" }
    }

    private companion object {
        const val TOMBSTONE_PREFIX = ".deleting-"
        val SAFE_SESSION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        val DELETABLE_STATUSES = setOf("COMPLETED", "FAILED", "ABORTED")
    }
}
