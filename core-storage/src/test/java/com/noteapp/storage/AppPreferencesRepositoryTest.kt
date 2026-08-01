package com.noteapp.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import com.noteapp.storage.proto.StoredAppPreferences
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppPreferencesRepositoryTest {
    @Test
    fun `proto defaults map to conservative app defaults`() = runBlocking {
        val repository = repository()

        assertEquals(AppPreferences(), repository.preferences.first())
    }

    @Test
    fun `updates typed transcription preferences transactionally`() = runBlocking {
        val repository = repository()

        repository.setCapturePipeline("native-48k-to-16k")
        repository.setIncrementalModel("sherpa-es")
        repository.setBenchmarkThreadCount(6)
        repository.setBenchmarkChunkSeconds(20)
        repository.setRetentionDays(90)
        repository.acknowledgeConsentNotice(123_456L)
        repository.setBiometricReauthenticationEnabled(true)

        assertEquals(
            AppPreferences(
                capturePipelineId = "native-48k-to-16k",
                incrementalModelId = "sherpa-es",
                benchmarkThreadCount = 6,
                benchmarkChunkSeconds = 20,
                retentionDays = 90,
                consentNoticeVersionAcknowledged = 1,
                consentAcknowledgedAtEpochMs = 123_456L,
                biometricReauthenticationEnabled = true,
            ),
            repository.preferences.first(),
        )

        repository.setIncrementalModel(null)
        assertNull(repository.preferences.first().incrementalModelId)
        repository.setBiometricReauthenticationEnabled(false)
        assertEquals(false, repository.preferences.first().biometricReauthenticationEnabled)
    }

    @Test
    fun `invalid values are rejected without changing stored preferences`() = runBlocking {
        val repository = repository()
        repository.setBenchmarkThreadCount(4)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setBenchmarkThreadCount(3) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setRetentionDays(7) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.acknowledgeConsentNotice(0L) }
        }

        assertEquals(4, repository.preferences.first().benchmarkThreadCount)
    }

    @Test
    fun `invalid stored values fall back independently`() = runBlocking {
        val dataStore = InMemoryDataStore(
            StoredAppPreferences.newBuilder()
                .setCapturePipelineId("unsupported")
                .setIncrementalAsrEnabled(true)
                .setIncrementalModelId("invalid model id")
                .setBenchmarkThreadCount(99)
                .setBenchmarkChunkSeconds(7)
                .setRetentionDays(7)
                .setConsentNoticeVersion(99)
                .setConsentAcknowledgedAtEpochMs(123L)
                .build(),
        )

        assertEquals(
            AppPreferences(),
            ProtoAppPreferencesRepository(dataStore).preferences.first(),
        )
    }

    @Test
    fun `serializer reports malformed protobuf as corruption`() {
        assertThrows(CorruptionException::class.java) {
            runBlocking {
                AppPreferencesSerializer.readFrom(
                    ByteArrayInputStream(byteArrayOf(0x0A, 0x05, 0x01)),
                )
            }
        }
    }

    private fun repository(): ProtoAppPreferencesRepository {
        return ProtoAppPreferencesRepository(InMemoryDataStore(StoredAppPreferences.getDefaultInstance()))
    }

    private class InMemoryDataStore<T>(initialValue: T) : DataStore<T> {
        private val state = MutableStateFlow(initialValue)

        override val data: Flow<T> = state

        override suspend fun updateData(transform: suspend (t: T) -> T): T {
            return transform(state.value).also { state.value = it }
        }
    }
}
