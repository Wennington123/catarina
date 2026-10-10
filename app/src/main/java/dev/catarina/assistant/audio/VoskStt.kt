package dev.catarina.assistant.audio

import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * STT em **portugues** com o Vosk: modelo pequeno pt-BR (~31 MB), offline e sem
 * Google, rodando no mesmo PCM 16 kHz mono que o resto do app ja grava.
 *
 * Existe porque o Whistle (STT do Needle) so entende en/de/fr/es/it/nl/pl — **nao
 * tem portugues** — e o aparelho alvo nao tem reconhecedor do sistema instalado
 * (sem Google), entao o `SpeechRecognizer` do Android acaba caindo no proprio app
 * e devolvendo a saida do Whistle. Com o Vosk o texto sai em PT de verdade.
 */
object VoskStt {

    const val SAMPLE_RATE = 16_000f
    private const val TAG = "VoskStt"

    @Volatile
    private var model: Model? = null

    val ready: Boolean get() = model != null

    /** Carrega a pasta do modelo (uma vez). `false` se a pasta estiver invalida. */
    fun load(dir: File): Boolean {
        if (model != null) return true
        return runCatching {
            model = Model(dir.absolutePath)
            Log.i(TAG, "modelo pt-BR carregado de ${dir.absolutePath}")
            true
        }.getOrElse {
            Log.w(TAG, "nao consegui carregar o modelo pt-BR em ${dir.absolutePath}", it)
            false
        }
    }

    /** Transcreve um enunciado (PCM float -1..1, 16 kHz). `null` se nao deu. */
    fun transcribe(pcm: FloatArray): String? {
        val loaded = model ?: return null
        if (pcm.isEmpty()) return null

        val samples = ShortArray(pcm.size) {
            (pcm[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }

        return runCatching {
            Recognizer(loaded, SAMPLE_RATE).use { rec ->
                rec.acceptWaveForm(samples, samples.size)
                val text = JSONObject(rec.finalResult()).optString("text").trim()
                Log.i(TAG, "ouvi: ${text.ifEmpty { "(nada)" }}")
                text.ifEmpty { null }
            }
        }.getOrElse {
            Log.w(TAG, "falha ao transcrever com o Vosk", it)
            null
        }
    }
}
