package dev.catarina.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.catarina.assistant.audio.AudioCapture
import dev.catarina.assistant.engine.NeedleEngine
import dev.catarina.assistant.model.ModelRepository
import dev.catarina.assistant.tools.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pipeline minimo, so para validar no dispositivo:
 * modelos -> engine -> audio -> tool call -> execucao -> TTS.
 * A versao de producao move isso para o `CatarinaService` (modelo quente).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var listen: Button

    private val executor by lazy { ToolExecutor(this) }
    private val capture by lazy { AudioCapture() }
    private val models by lazy { (application as CatarinaApp).models }
    private var tts: TextToSpeech? = null
    private var loaded = false

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startTurn() else status.text = "Permissao de microfone negada."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        listen = findViewById(R.id.listenButton)

        tts = TextToSpeech(this) { /* TODO: verificar status do motor de TTS */ }

        listen.setOnClickListener {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) startTurn() else requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun startTurn() {
        listen.isEnabled = false
        lifecycleScope.launch {
            try {
                ensureLoaded()

                status.text = getString(R.string.status_listening)
                val pcm = withContext(Dispatchers.IO) { capture.recordUtterance() }
                if (pcm.isEmpty()) {
                    status.text = "Nao ouvi nada."
                    return@launch
                }

                val response = withContext(Dispatchers.IO) { NeedleEngine.completeAudio(pcm) }
                if (response == null) {
                    status.text = "Falha no engine: ${NeedleEngine.lastError()}"
                    return@launch
                }

                if (response.isRefusal) {
                    status.text = "Nao consigo fazer isso."
                    say("Nao consigo fazer isso.")
                    return@launch
                }

                val confident = (response.confidence ?: 1.0) >= CONFIDENCE_FLOOR
                val calls = if (confident) response.functionCalls else response.bestCalls

                if (!confident) {
                    // Abaixo do limiar: pedir confirmacao em vez de agir.
                    val names = calls.joinToString(", ") { it.name }
                    status.text = "Confirma? $names"
                    say("Confirma $names?")
                    return@launch
                }

                val results = calls.map { executor.execute(it) }
                status.text = results.joinToString("\n") { it.detail }
                say(results.joinToString(", ") { it.detail })
            } catch (t: Throwable) {
                status.text = "Erro: ${t.message}"
            } finally {
                listen.isEnabled = true
            }
        }
    }

    /** Baixa (1x) e carrega os modelos; idempotente entre turnos. */
    private suspend fun ensureLoaded() {
        if (loaded) return
        status.text = getString(R.string.status_downloading)
        withContext(Dispatchers.IO) {
            val needle = models.ensure(ModelRepository.NEEDLE3)
            val whistle = models.ensure(ModelRepository.WHISTLE)
            val tools = assets.open("tools.json").bufferedReader().use { it.readText() }
            NeedleEngine.load(needle)
            NeedleEngine.load(whistle)
            NeedleEngine.init(systemPrompt = "device: phone; locale: pt-BR", toolsJson = tools)
        }
        loaded = true
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "catarina")
    }

    private companion object {
        const val CONFIDENCE_FLOOR = 0.7
    }
}
