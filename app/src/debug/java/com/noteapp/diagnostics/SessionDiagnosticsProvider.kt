package com.noteapp.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.noteapp.audio.AudioSessionWriter
import com.noteapp.audio.PcmFormat
import com.noteapp.security.AndroidKeystoreSessionArtifactStore
import java.io.File
import org.json.JSONObject

/**
 * Same-UID, debug-only diagnostics for reproducible device harnesses.
 *
 * No audio, transcript, model output, file path, or session content is returned.
 */
class SessionDiagnosticsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceSameUid()
        val sessionId = sessionId(uri)
        val checkpoint = readCheckpoint(sessionId)
        return MatrixCursor(QUERY_COLUMNS).apply {
            addRow(
                arrayOf(
                    checkpoint.getString("status"),
                    checkpoint.getLong("durationMs"),
                    checkpoint.getLong("totalBytes"),
                    checkpoint.getJSONArray("segments").length(),
                    checkpoint.optInt("readErrorCount", 0),
                    checkpoint.optInt("discontinuityCount", 0),
                    checkpoint.optLong("estimatedMissingFrames", 0L),
                    false,
                ),
            )
        }
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        enforceSameUid()
        check(method == METHOD_PREPARE_RECOVERY) { "DIAGNOSTIC_METHOD_UNSUPPORTED" }
        val sessionId = requireNotNull(arg).also(::requireSafeSessionId)
        val appContext = requireNotNull(context).applicationContext
        val artifactStore = AndroidKeystoreSessionArtifactStore.create(appContext)
        val recovered = AudioSessionWriter.recover(
            rootDirectory = File(appContext.filesDir, RECORDINGS_DIRECTORY),
            sessionId = sessionId,
            expectedFormat = PcmFormat(SAMPLE_RATE_HZ),
            artifactStore = artifactStore,
        )
        return Bundle().apply {
            putString("result", "RECOVERY_PREFIX_AUTHENTICATED")
            putLong("recoveredBytes", recovered.totalBytes)
            putInt("recoveredSegmentCount", recovered.completedSegments.size)
            putBoolean("contentIncluded", false)
        }
    }

    override fun getType(uri: Uri): String {
        enforceSameUid()
        sessionId(uri)
        return MIME_TYPE
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("DIAGNOSTIC_PROVIDER_READ_ONLY")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("DIAGNOSTIC_PROVIDER_READ_ONLY")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("DIAGNOSTIC_PROVIDER_READ_ONLY")

    private fun readCheckpoint(sessionId: String): JSONObject {
        val appContext = requireNotNull(context).applicationContext
        val checkpoint = File(appContext.filesDir, "$RECORDINGS_DIRECTORY/$sessionId/checkpoint.json")
        check(checkpoint.isFile) { "DIAGNOSTIC_SESSION_NOT_FOUND" }
        val artifactStore = AndroidKeystoreSessionArtifactStore.create(appContext)
        return JSONObject(artifactStore.readText(checkpoint))
    }

    private fun sessionId(uri: Uri): String {
        check(uri.authority == "${requireNotNull(context).packageName}.diagnostics") {
            "DIAGNOSTIC_AUTHORITY_INVALID"
        }
        check(uri.pathSegments.size == 2 && uri.pathSegments[0] == "session") {
            "DIAGNOSTIC_URI_INVALID"
        }
        return uri.pathSegments[1].also(::requireSafeSessionId)
    }

    private fun requireSafeSessionId(sessionId: String) {
        require(sessionId.matches(SAFE_SESSION_ID)) { "DIAGNOSTIC_SESSION_ID_INVALID" }
    }

    private fun enforceSameUid() {
        check(Binder.getCallingUid() == Process.myUid()) {
            "DIAGNOSTIC_CALLER_REJECTED"
        }
    }

    private companion object {
        const val METHOD_PREPARE_RECOVERY = "prepare-recovery"
        const val RECORDINGS_DIRECTORY = "recordings"
        const val SAMPLE_RATE_HZ = 16_000
        const val MIME_TYPE = "vnd.android.cursor.item/vnd.com.noteapp.session-diagnostics"
        val SAFE_SESSION_ID = Regex("[A-Za-z0-9-]+")
        val QUERY_COLUMNS = arrayOf(
            "status",
            "durationMs",
            "totalBytes",
            "segmentCount",
            "readErrorCount",
            "discontinuityCount",
            "estimatedMissingFrames",
            "contentIncluded",
        )
    }
}
