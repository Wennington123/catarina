package dev.catarina.assistant.assistant

import android.content.Intent
import android.speech.RecognitionService

/**
 * Ponte de reconhecimento de fala do sistema para o Whistle.
 * TODO: alimentar o engine com o PCM e devolver os resultados via `Callback`.
 */
class CatarinaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) = Unit
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
