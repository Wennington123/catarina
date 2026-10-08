package dev.catarina.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * Captura audio em 16 kHz mono (exigencia do Whistle) e corta a fala por um VAD
 * simples de energia (RMS), para nao mandar silencio ao modelo.
 */
class AudioCapture(
    private val sampleRate: Int = SAMPLE_RATE,
    private val silenceRms: Float = 0.008f,
    private val silenceHoldMs: Int = 700,
    private val minSpeechMs: Int = 250,
    private val maxUtteranceMs: Int = 15_000,
) {
    /** Grava um enunciado; para quando detecta silencio depois de ter ouvido fala. */
    @SuppressLint("MissingPermission")
    fun recordUtterance(): FloatArray {
        val frame = sampleRate / 50 // 20 ms
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                ),
                frame * 4,
            ),
        )

        val out = ArrayList<Float>(sampleRate * 5)
        val buf = FloatArray(frame)
        var speechMs = 0
        var silenceMs = 0
        var heardSpeech = false

        rec.startRecording()
        try {
            while (true) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) break

                out.ensureCapacity(out.size + n)
                var sum = 0f
                for (i in 0 until n) {
                    out.add(buf[i])
                    sum += buf[i] * buf[i]
                }
                val rms = sqrt(sum / n)
                val ms = n * 1000 / sampleRate
                if (rms >= silenceRms) {
                    heardSpeech = true
                    speechMs += ms
                    silenceMs = 0
                } else if (heardSpeech) {
                    silenceMs += ms
                }

                val totalMs = out.size * 1000 / sampleRate
                if (heardSpeech && speechMs >= minSpeechMs && silenceMs >= silenceHoldMs) break
                if (totalMs >= maxUtteranceMs) break
            }
        } finally {
            rec.stop()
            rec.release()
        }

        return out.toFloatArray()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
