package dev.catarina.assistant.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Voz em portugues pelo reconhecedor do **Android**.
 *
 * O Whistle (STT do Needle) nao tem portugues: a lista oficial e ingles, alemao,
 * frances, espanhol, italiano, holandes e polones. Com ele, fala em PT vira lixo
 * e nada casa com os `triggers`. Aqui o texto vem do reconhecedor do sistema em
 * pt-BR e segue pro Needle, que continua decidindo a ferramenta — o resto do
 * pipeline nao muda.
 *
 * Custo: o reconhecedor do sistema costuma ser do Google, entao quebra o
 * "zero Google" do modo local. Pedimos `EXTRA_PREFER_OFFLINE` (usa o pacote de
 * idioma baixado quando existe) e deixamos isso atras de um toggle.
 *
 * Precisa rodar na **main thread**: `SpeechRecognizer` usa o `Looper` da thread
 * que o criou.
 */
object AndroidStt {

    private const val TAG = "AndroidStt"

    const val LANGUAGE = "pt-BR"
    private const val DEFAULT_TIMEOUT_MS = 15_000L

    fun available(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /** Ouve um enunciado e devolve o texto reconhecido (null = silencio/erro). */
    suspend fun listen(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) { awaitOnce(context) }
        }

    private suspend fun awaitOnce(context: Context): String? =
        suspendCancellableCoroutine { cont ->
            val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
                .getOrElse {
                    Log.w(TAG, "sem reconhecedor de voz neste aparelho", it)
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }

            var finished = false
            fun finish(text: String?) {
                if (finished) return
                finished = true
                runCatching { recognizer.stopListening() }
                runCatching { recognizer.destroy() }
                if (cont.isActive) cont.resume(text)
            }

            recognizer.setRecognitionListener(
                object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val said = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        finish(said?.ifEmpty { null })
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "reconhecedor devolveu erro $error")
                        finish(null)
                    }

                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                },
            )

            cont.invokeOnCancellation { finish(null) }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            runCatching { recognizer.startListening(intent) }.onFailure { finish(null) }
        }
}
