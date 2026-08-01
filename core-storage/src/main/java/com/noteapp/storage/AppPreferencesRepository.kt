package com.noteapp.storage

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.google.protobuf.InvalidProtocolBufferException
import com.noteapp.storage.proto.StoredAppPreferences
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppPreferences(
    val capturePipelineId: String = DEFAULT_CAPTURE_PIPELINE_ID,
    val incrementalModelId: String? = null,
    val benchmarkThreadCount: Int = DEFAULT_BENCHMARK_THREAD_COUNT,
    val benchmarkChunkSeconds: Int = DEFAULT_BENCHMARK_CHUNK_SECONDS,
    val retentionDays: Int = RETENTION_FOREVER_DAYS,
    val consentNoticeVersionAcknowledged: Int = 0,
    val consentAcknowledgedAtEpochMs: Long? = null,
) {
    companion object {
        const val DEFAULT_CAPTURE_PIPELINE_ID = "direct-16k"
        const val DEFAULT_BENCHMARK_THREAD_COUNT = 4
        const val DEFAULT_BENCHMARK_CHUNK_SECONDS = 30
        const val RETENTION_FOREVER_DAYS = 0
        const val CURRENT_CONSENT_NOTICE_VERSION = 1
        val SUPPORTED_RETENTION_DAYS = setOf(RETENTION_FOREVER_DAYS, 30, 90, 365)
    }
}

object AppPreferencesSerializer : Serializer<StoredAppPreferences> {
    override val defaultValue: StoredAppPreferences =
        StoredAppPreferences.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): StoredAppPreferences =
        try {
            StoredAppPreferences.parseFrom(input)
        } catch (failure: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read app preferences proto", failure)
        }

    override suspend fun writeTo(
        t: StoredAppPreferences,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}

fun createAppPreferencesRepository(context: Context): AppPreferencesStore =
    ProtoAppPreferencesRepository(
        DataStoreFactory.create(
            serializer = AppPreferencesSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler {
                StoredAppPreferences.getDefaultInstance()
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = {
                context.applicationContext.dataStoreFile(DATA_STORE_FILE_NAME)
            },
        ),
    )

interface AppPreferencesStore {
    val preferences: Flow<AppPreferences>

    suspend fun setCapturePipeline(id: String)

    suspend fun setIncrementalModel(id: String?)

    suspend fun setBenchmarkThreadCount(count: Int)

    suspend fun setBenchmarkChunkSeconds(seconds: Int)

    suspend fun setRetentionDays(days: Int)

    suspend fun acknowledgeConsentNotice(atEpochMs: Long)
}

internal class ProtoAppPreferencesRepository(
    private val dataStore: DataStore<StoredAppPreferences>,
) : AppPreferencesStore {
    override val preferences: Flow<AppPreferences> = dataStore.data.map(::toAppPreferences)

    override suspend fun setCapturePipeline(id: String) {
        require(id in CAPTURE_PIPELINE_IDS) { "INVALID_CAPTURE_PIPELINE" }
        dataStore.updateData { current ->
            current.toBuilder().setCapturePipelineId(id).build()
        }
    }

    override suspend fun setIncrementalModel(id: String?) {
        require(id == null || id.matches(MODEL_ID)) { "INVALID_INCREMENTAL_MODEL" }
        dataStore.updateData { current ->
            current.toBuilder()
                .setIncrementalAsrEnabled(id != null)
                .setIncrementalModelId(id.orEmpty())
                .build()
        }
    }

    override suspend fun setBenchmarkThreadCount(count: Int) {
        require(count in BENCHMARK_THREAD_COUNTS) { "INVALID_BENCHMARK_THREAD_COUNT" }
        dataStore.updateData { current ->
            current.toBuilder().setBenchmarkThreadCount(count).build()
        }
    }

    override suspend fun setBenchmarkChunkSeconds(seconds: Int) {
        require(seconds in BENCHMARK_CHUNK_SECONDS) { "INVALID_BENCHMARK_CHUNK_SECONDS" }
        dataStore.updateData { current ->
            current.toBuilder().setBenchmarkChunkSeconds(seconds).build()
        }
    }

    override suspend fun setRetentionDays(days: Int) {
        require(days in AppPreferences.SUPPORTED_RETENTION_DAYS) { "INVALID_RETENTION_DAYS" }
        dataStore.updateData { current ->
            current.toBuilder().setRetentionDays(days).build()
        }
    }

    override suspend fun acknowledgeConsentNotice(atEpochMs: Long) {
        require(atEpochMs > 0L) { "INVALID_CONSENT_TIMESTAMP" }
        dataStore.updateData { current ->
            current.toBuilder()
                .setConsentNoticeVersion(AppPreferences.CURRENT_CONSENT_NOTICE_VERSION)
                .setConsentAcknowledgedAtEpochMs(atEpochMs)
                .build()
        }
    }

    private fun toAppPreferences(stored: StoredAppPreferences): AppPreferences =
        AppPreferences(
            capturePipelineId = stored.capturePipelineId
                .takeIf { it in CAPTURE_PIPELINE_IDS }
                ?: AppPreferences.DEFAULT_CAPTURE_PIPELINE_ID,
            incrementalModelId = stored.incrementalModelId
                .takeIf { stored.incrementalAsrEnabled && it.matches(MODEL_ID) },
            benchmarkThreadCount = stored.benchmarkThreadCount
                .takeIf { it in BENCHMARK_THREAD_COUNTS }
                ?: AppPreferences.DEFAULT_BENCHMARK_THREAD_COUNT,
            benchmarkChunkSeconds = stored.benchmarkChunkSeconds
                .takeIf { it in BENCHMARK_CHUNK_SECONDS }
                ?: AppPreferences.DEFAULT_BENCHMARK_CHUNK_SECONDS,
            retentionDays = stored.retentionDays
                .takeIf { it in AppPreferences.SUPPORTED_RETENTION_DAYS }
                ?: AppPreferences.RETENTION_FOREVER_DAYS,
            consentNoticeVersionAcknowledged = stored.consentNoticeVersion
                .takeIf {
                    it == AppPreferences.CURRENT_CONSENT_NOTICE_VERSION &&
                        stored.consentAcknowledgedAtEpochMs > 0L
                }
                ?: 0,
            consentAcknowledgedAtEpochMs = stored.consentAcknowledgedAtEpochMs
                .takeIf {
                    stored.consentNoticeVersion == AppPreferences.CURRENT_CONSENT_NOTICE_VERSION &&
                        it > 0L
                },
        )

    private companion object {
        val CAPTURE_PIPELINE_IDS = setOf("direct-16k", "native-48k-to-16k")
        val BENCHMARK_THREAD_COUNTS = setOf(2, 4, 6, 8)
        val BENCHMARK_CHUNK_SECONDS = setOf(10, 20, 30)
        val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
    }
}

private const val DATA_STORE_FILE_NAME = "app_preferences.pb"
