package dev.catarina.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.catarina.assistant.audio.AndroidStt
import dev.catarina.assistant.audio.AudioCapture
import dev.catarina.assistant.audio.MascotSound
import dev.catarina.assistant.audio.MascotSounds
import dev.catarina.assistant.audio.reactToTurn
import dev.catarina.assistant.core.CatarinaBrain
import dev.catarina.assistant.service.CatarinaService
import dev.catarina.assistant.state.AppSettings
import dev.catarina.assistant.state.AssistantStateBus
import dev.catarina.assistant.state.Phase
import dev.catarina.assistant.ui.CatarinaTheme
import dev.catarina.assistant.ui.MainScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI em Compose: a bebezinha reage ao estado; um turno manual por vez (para
 * testar no device), download dos pesos com anel de progresso e o modo
 * continuo do service.
 */
class MainActivity : AppCompatActivity() {

    private val brain: CatarinaBrain get() = (application as CatarinaApp).brain
    private val capture = AudioCapture()
    private var turnJob: Job? = null

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CatarinaTheme {
                MainScreen(
                    onListen = ::onListen,
                    onToggleService = ::onToggleService,
                    onGrantPermissions = { requestPermissions.launch(PERMISSIONS) },
                )
            }
        }
    }

    /** O service roda como foreground do tipo microfone: precisa de RECORD_AUDIO antes. */
    private fun onToggleService() {
        if (AssistantStateBus.base == Phase.LISTENING) {
            CatarinaService.stop(this)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions.launch(PERMISSIONS)
            return
        }
        CatarinaService.start(this)
    }

    private fun onListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions.launch(PERMISSIONS)
            return
        }
        if (turnJob?.isActive == true) return

        turnJob = lifecycleScope.launch {
            try {
                brain.ensureLoaded()
                AssistantStateBus.set(Phase.HEARING)
                MascotSounds.play(MascotSound.HEAR)
                val turn = if (AppSettings.androidStt) {
                    val heard = AndroidStt.listen(this@MainActivity)
                    AssistantStateBus.set(Phase.THINKING)
                    if (heard.isNullOrBlank()) {
                        CatarinaBrain.Turn(
                            "Nao entendi o audio.",
                            "Nao entendi.",
                            CatarinaBrain.Turn.Kind.REFUSED,
                        )
                    } else {
                        brain.handleText(heard)
                    }
                } else {
                    val pcm = withContext(Dispatchers.IO) { capture.recordUtterance() }
                    AssistantStateBus.set(Phase.THINKING)
                    brain.handleAudio(pcm)
                }
                AssistantStateBus.updateMessage(turn.status)
                reactToTurn(turn)
            } catch (t: Throwable) {
                AssistantStateBus.updateMessage("Erro: ${t.message}")
                AssistantStateBus.flash(Phase.SAD)
                MascotSounds.play(MascotSound.SAD)
            }
        }
    }

    private companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.SEND_SMS,
        )
    }
}
