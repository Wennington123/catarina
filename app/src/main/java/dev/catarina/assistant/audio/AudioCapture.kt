package dev.catarina.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * Captura audio em 16 kHz mono (exigencia do Whistle) e corta a fala por um VAD
 * simples de energia (RMS), para nao mandar silencio ao modelo.
 *
 * Duas instancias desta classe disputam o microfone (wake word e turno), entao
 * a gravacao e **cancelavel**: [cancel] para o `AudioRecord` na hora. So setar
 * uma flag e dar `Thread.interrupt()` nao basta — `AudioRecord.read` bloqueado
 * nao responde a interrupcao e o microfone fica preso (era a causa do "nao
 * ouve nada" no Android).
 */
class AudioCapture(
    private val sampleRate: Int = SAMPLE_RATE,
    private val silenceRms: Float = 0.008f,
    private val silenceHoldMs: Int = 700,
    private val minSpeechMs: Int = 250,
    private val maxUtteranceMs: Int = 15_000,
) {
    @Volatile
    private var active: AudioRecord? = null

    /**
     * Marcado por [cancel] e so limpo por [resume]. E "sticky" de proposito:
     * um cancel que chega entre duas gravacoes nao pode ser perdido, senao o
     * wake word reabre o microfone em cima do turno.
     */
    @Volatile
    private var stopped = false

    /** Libera o mic para quem pegou o turno: destrava o `read` bloqueado. */
    fun cancel() {
        stopped = true
        runCatching { active?.stop() }
    }

    /** Autoriza uma nova gravacao. Chamar so quando realmente for gravar. */
    fun resume() {
        stopped = false
    }

    /** Grava um enunciado; para quando detecta silencio depois de ter ouvido fala. */
    @SuppressLint("MissingPermission")
    fun recordUtterance(): FloatArray {
        if (stopped) return FloatArray(0)
        val frame = sampleRate / 50 // 20 ms
        val out = ArrayList<Float>(sampleRate * 5)
        val buf = FloatArray(frame)
        var speechMs = 0
        var silenceMs = 0
        var heardSpeech = false

        val rec = openRecord(frame) ?: return FloatArray(0)
        active = rec
        rec.startRecording()
        try {
            while (!stopped) {
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
            runCatching { rec.stop() }
            rec.release()
            active = null
        }

        return out.toFloatArray()
    }

    /**
     * Abre o microfone tentando as fontes em ordem. Retorna null se nenhuma
     * inicializar (ex.: mic ainda ocupado pela outra gravacao) — quem chamou
     * decide o que fazer com o silencio.
     */
    @SuppressLint("MissingPermission")
    private fun openRecord(frame: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferSize = maxOf(if (minBuf > 0) minBuf else 0, frame * 4)

        for (source in SOURCES) {
            val rec = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    bufferSize,
                )
            }.getOrNull() ?: continue

            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            runCatching { rec.release() }
        }

        Log.w(TAG, "AudioRecord nao inicializou em nenhuma fonte: ${SOURCES.toList()}")
        return null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "AudioCapture"

        /** VOICE_RECOGNITION limpa ruido/AEC; MIC cru e o fallback. */
        private val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
    }
}
