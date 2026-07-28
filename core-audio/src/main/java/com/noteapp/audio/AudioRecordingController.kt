package com.noteapp.audio

import android.content.Context
import android.content.Intent

class AudioRecordingController(context: Context) {
    private val applicationContext = context.applicationContext

    val state = RecordingRuntime.state

    fun start(
        pipeline: CapturePipeline = CapturePipeline.DIRECT_16_KHZ,
        incrementalModelId: String? = null,
    ) = send(
        AudioCaptureService.ACTION_START,
        foreground = true,
        capturePipeline = pipeline,
        incrementalModelId = incrementalModelId,
    )
    fun recover(sessionId: String) = send(
        AudioCaptureService.ACTION_RECOVER,
        foreground = true,
        sessionId = sessionId,
    )
    fun pause() = send(AudioCaptureService.ACTION_PAUSE)
    fun resume() = send(AudioCaptureService.ACTION_RESUME)
    fun complete() = send(AudioCaptureService.ACTION_COMPLETE)
    fun abort() = send(AudioCaptureService.ACTION_ABORT)

    private fun send(
        action: String,
        foreground: Boolean = false,
        sessionId: String? = null,
        capturePipeline: CapturePipeline? = null,
        incrementalModelId: String? = null,
    ) {
        val intent = Intent(applicationContext, AudioCaptureService::class.java)
            .setAction(action)
            .apply {
                sessionId?.let { putExtra(AudioCaptureService.EXTRA_SESSION_ID, it) }
                capturePipeline?.let { putExtra(AudioCaptureService.EXTRA_CAPTURE_PIPELINE, it.id) }
                incrementalModelId?.let {
                    putExtra(AudioCaptureService.EXTRA_INCREMENTAL_MODEL_ID, it)
                }
                putExtra(AudioCaptureService.EXTRA_COMMAND_SOURCE, AudioCaptureService.SOURCE_UI)
            }
        if (foreground) applicationContext.startForegroundService(intent)
        else applicationContext.startService(intent)
    }
}
