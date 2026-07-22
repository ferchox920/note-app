package com.noteapp.vad

import android.content.Context
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

class SileroFrameVoiceDetector(context: Context) : FrameVoiceDetector {
    private val delegate = VadSilero(
        context = context.applicationContext,
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_512,
        mode = Mode.NORMAL,
        speechDurationMs = 0,
        silenceDurationMs = 0,
    )

    override val sampleRateHz: Int = 16_000
    override val frameSizeSamples: Int = 512

    override fun isSpeech(pcm16LittleEndian: ByteArray): Boolean {
        require(pcm16LittleEndian.size == frameSizeSamples * 2)
        return delegate.isSpeech(pcm16LittleEndian)
    }

    override fun close() = delegate.close()
}
