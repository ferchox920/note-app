package com.noteapp.vad

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.io.Closeable

interface FrameVoiceDetector : Closeable {
    val sampleRateHz: Int
    val frameSizeSamples: Int
    fun isSpeech(pcm16LittleEndian: ByteArray): Boolean
}

class WebRtcFrameVoiceDetector(
    private val delegate: VadWebRTC = VadWebRTC(
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_320,
        mode = Mode.AGGRESSIVE,
        speechDurationMs = 0,
        silenceDurationMs = 0,
    ),
) : FrameVoiceDetector {
    override val sampleRateHz: Int = 16_000
    override val frameSizeSamples: Int = 320

    override fun isSpeech(pcm16LittleEndian: ByteArray): Boolean {
        require(pcm16LittleEndian.size == frameSizeSamples * PCM16_BYTES_PER_SAMPLE)
        return delegate.isSpeech(pcm16LittleEndian)
    }

    override fun close() = delegate.close()

    private companion object {
        const val PCM16_BYTES_PER_SAMPLE = 2
    }
}

