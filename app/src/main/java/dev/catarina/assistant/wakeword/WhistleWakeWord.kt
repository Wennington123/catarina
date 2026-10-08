package dev.catarina.assistant.wakeword

import dev.catarina.assistant.audio.AudioCapture
import dev.catarina.assistant.engine.NeedleEngine
import org.json.JSONObject

/**
 * Wake word "de software": transcreve continuamente com o Whistle e dispara
 * quando o texto contem a frase ("catarina"). Nao adiciona dependencia nenhuma,
 * mas mantem microfone + engine ligados — custo de bateria. Para always-on de
 * verdade, trocar por um KWS dedicado (ex.: sherpa-onnx).
 */
class WhistleWakeWord(private val phrase: String = "catarina") : WakeWordDetector {

    @Volatile
    private var running = false

    private var thread: Thread? = null

    private val capture = AudioCapture(silenceHoldMs = 400, maxUtteranceMs = 3_000)

    override fun start(onDetected: (transcript: String) -> Unit) {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                // resume() antes de checar `running`: se um stop() correu no meio,
                // o flag fica "stopped" e recordUtterance devolve vazio (nao rouba
                // o microfone do turno).
                capture.resume()
                if (!running) break
                val pcm = capture.recordUtterance()
                if (!running) break
                if (pcm.isEmpty()) continue

                val json = NeedleEngine.transcribe(pcm) ?: continue
                val text = runCatching { JSONObject(json).optString("text") }.getOrDefault("")
                if (text.lowercase().contains(phrase)) {
                    running = false
                    onDetected(text)
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        // Destrava o read bloqueado e libera o microfone para o turno. Sem isto
        // o `AudioRecord` do wake word fica preso e o turno grava silencio.
        capture.cancel()
        thread = null
    }
}
