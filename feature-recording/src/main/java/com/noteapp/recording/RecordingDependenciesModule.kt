package com.noteapp.recording

import android.content.Context
import com.noteapp.asr.AsrLabRunner
import com.noteapp.asr.SherpaStreamingLabRunner
import com.noteapp.audio.AudioRecordingController
import com.noteapp.storage.NoteAppDatabase
import com.noteapp.storage.AppPreferencesStore
import com.noteapp.storage.ProcessingTelemetryStore
import com.noteapp.storage.RoomProcessingTelemetryStore
import com.noteapp.storage.RoomSessionCheckpointStore
import com.noteapp.storage.SessionCheckpointStore
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
    fun provideNoteAppDatabase(
        @ApplicationContext context: Context,
    ): NoteAppDatabase = NoteAppDatabase.create(context)

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
    ): SessionCheckpointStore = RoomSessionCheckpointStore(
        File(context.filesDir, "recordings"),
        database,
    )

    @Provides
    @Singleton
    fun provideProcessingTelemetryStore(
        database: NoteAppDatabase,
    ): ProcessingTelemetryStore = RoomProcessingTelemetryStore(database)

    @Provides
    @Singleton
    fun provideAsrLabRunner(@ApplicationContext context: Context): AsrLabRunner = AsrLabRunner(context)

    @Provides
    @Singleton
    fun provideSherpaStreamingLabRunner(
        @ApplicationContext context: Context,
    ): SherpaStreamingLabRunner = SherpaStreamingLabRunner(context)

    @Provides
    fun provideVadComparisonRunner(
        @ApplicationContext context: Context,
    ): VadComparisonRunner = VadComparisonRunner(context)
}
