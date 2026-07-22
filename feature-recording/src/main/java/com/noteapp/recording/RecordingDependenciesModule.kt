package com.noteapp.recording

import android.content.Context
import com.noteapp.asr.AsrLabRunner
import com.noteapp.audio.AudioRecordingController
import com.noteapp.storage.FileSessionCheckpointStore
import com.noteapp.storage.SessionCheckpointStore
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
    fun provideSessionCheckpointStore(
        @ApplicationContext context: Context,
    ): SessionCheckpointStore = FileSessionCheckpointStore(File(context.filesDir, "recordings"))

    @Provides
    @Singleton
    fun provideAsrLabRunner(@ApplicationContext context: Context): AsrLabRunner = AsrLabRunner(context)

    @Provides
    fun provideVadComparisonRunner(
        @ApplicationContext context: Context,
    ): VadComparisonRunner = VadComparisonRunner(context)
}
