package com.noteapp.recording

import android.content.Context
import com.noteapp.asr.AsrLabRunner
import com.noteapp.asr.SherpaStreamingLabRunner
import com.noteapp.audio.AudioRecordingController
import com.noteapp.security.AndroidKeystoreDatabasePassphraseProvider
import com.noteapp.security.AndroidKeystoreSessionArtifactStore
import com.noteapp.security.DatabasePassphraseProvider
import com.noteapp.security.SessionArtifactStore
import com.noteapp.storage.NoteAppDatabase
import com.noteapp.storage.AppPreferencesStore
import com.noteapp.storage.ProcessingTelemetryStore
import com.noteapp.storage.RoomProcessingTelemetryStore
import com.noteapp.storage.RoomSessionDeletionStore
import com.noteapp.storage.RoomSessionCheckpointStore
import com.noteapp.storage.SessionCheckpointStore
import com.noteapp.storage.SessionDeletionStore
import com.noteapp.storage.createAppPreferencesRepository
import com.noteapp.vad.VadComparisonRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RecordingDependenciesModule {
    @Provides
    @Singleton
    fun provideAudioRecordingController(
        @ApplicationContext context: Context,
    ): AudioRecordingController = AudioRecordingController(context)

    @Provides
    @Singleton
    fun provideDatabasePassphraseProvider(
        @ApplicationContext context: Context,
    ): DatabasePassphraseProvider =
        AndroidKeystoreDatabasePassphraseProvider(context)

    @Provides
    @Singleton
    fun provideSessionArtifactStore(
        @ApplicationContext context: Context,
    ): SessionArtifactStore = AndroidKeystoreSessionArtifactStore.create(context)

    @Provides
    @Singleton
    fun provideNoteAppDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: DatabasePassphraseProvider,
    ): NoteAppDatabase = NoteAppDatabase.create(context, passphraseProvider)

    @Provides
    @Singleton
    fun provideAppPreferencesStore(
        @ApplicationContext context: Context,
    ): AppPreferencesStore = createAppPreferencesRepository(context)

    @Provides
    @Singleton
    fun provideSessionCheckpointStore(
        @ApplicationContext context: Context,
        database: NoteAppDatabase,
        artifactStore: SessionArtifactStore,
    ): SessionCheckpointStore = RoomSessionCheckpointStore(
        File(context.filesDir, "recordings"),
        database,
        artifactStore,
    )

    @Provides
    @Singleton
    fun provideProcessingTelemetryStore(
        database: NoteAppDatabase,
        checkpointStore: SessionCheckpointStore,
    ): ProcessingTelemetryStore = RoomProcessingTelemetryStore(
        database = database,
        ensureSessionIndexed = { sessionId ->
            check(checkpointStore.findCompleted().any { session -> session.id == sessionId }) {
                "SESSION_NOT_COMPLETED"
            }
        },
    )

    @Provides
    @Singleton
    fun provideSessionDeletionStore(
        @ApplicationContext context: Context,
        database: NoteAppDatabase,
    ): SessionDeletionStore = RoomSessionDeletionStore(
        recordingsDirectory = File(context.filesDir, "recordings"),
        database = database,
    )

    @Provides
    @Singleton
    fun provideAsrLabRunner(
        @ApplicationContext context: Context,
        artifactStore: SessionArtifactStore,
    ): AsrLabRunner = AsrLabRunner(context, artifactStore)

    @Provides
    @Singleton
    fun provideSherpaStreamingLabRunner(
        @ApplicationContext context: Context,
        artifactStore: SessionArtifactStore,
    ): SherpaStreamingLabRunner = SherpaStreamingLabRunner(context, artifactStore)

    @Provides
    fun provideVadComparisonRunner(
        @ApplicationContext context: Context,
        artifactStore: SessionArtifactStore,
    ): VadComparisonRunner = VadComparisonRunner(context, artifactStore)
}
