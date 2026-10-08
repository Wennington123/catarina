package dev.catarina.assistant.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.catarina.assistant.CatarinaApp
import dev.catarina.assistant.audio.AudioCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Ponte de reconhecimento de fala do sistema para o Whistle: grava um enunciado,
 * transcreve on-device e devolve o texto ao chamador.
 */
class CatarinaRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val capture = AudioCapture()
    private var job: Job? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (listener == null) return
        job?.cancel()
        job = scope.launch {
            try {
                val brain = (application as CatarinaApp).brain
                brain.ensureLoaded()
                val pcm = capture.recordUtterance()
                val json = brain.transcribe(pcm)
                val text = json?.let { runCatching { JSONObject(it).optString("text") }.getOrDefault("") }.orEmpty()

                listener.results(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                })
                listener.endOfSpeech()
            } catch (t: Throwable) {
                listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            }
        }
    }

    override fun onCancel(listener: Callback?) {
        job?.cancel()
        listener?.endOfSpeech()
    }

    override fun onStopListening(listener: Callback?) = Unit

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
    }
}
