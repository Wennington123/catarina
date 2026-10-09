package dev.catarina.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.catarina.assistant.CatarinaApp
import dev.catarina.assistant.MainActivity
import dev.catarina.assistant.R
import dev.catarina.assistant.audio.AudioCapture
import dev.catarina.assistant.core.CatarinaBrain
import dev.catarina.assistant.core.CatarinaBrain.Turn
import dev.catarina.assistant.state.AssistantStateBus
import dev.catarina.assistant.state.Phase
import dev.catarina.assistant.wakeword.WhistleWakeWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Dono do modelo quente e do loop de voz. O engine e process-global e nao
 * thread-safe, entao este service e o lugar unico que mantem a conversa em modo
 * continuo.
 *
 * Acoes:
 *  - ACTION_START: liga o modo escuta (wake word)
 *  - ACTION_LISTEN: um turno agora
 *  - ACTION_TEXT: processa um texto (extra)
 *  - ACTION_STOP / stopService: desliga
 */
class CatarinaService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val brain: CatarinaBrain get() = (application as CatarinaApp).brain
    private val capture = AudioCapture()

    private var tts: TextToSpeech? = null
    private var wakeWord: WhistleWakeWord? = null

    @Volatile private var listening = false
    @Volatile private var busy = false

    /** O TTS inicializa de forma assincrona; fala pedida antes disso fica em espera. */
    @Volatile private var ttsReady = false
    private var pendingSpeech: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
            } else {
                // Init com erro: ainda tentamos falar (alguns motores retornam
                // ERROR mesmo funcionando). Sem configurar, o timbre nao muda.
                Log.w(TAG, "TTS init retornou $status")
                ttsReady = true
                pendingSpeech?.let(::speakNow)
                pendingSpeech = null
            }
        }
    }

    /**
     * Escolhe idioma/voz e aplica o timbre. Os motivos do "nao fala" eram: idioma
     * nao conferido (pt-BR pode nao existir no aparelho), sem `AudioAttributes`
     * (ia pra um stream mudo) e `speak` disparado antes do init terminar.
     */
    private fun configureTts() {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )

        val ptBr = Locale("pt", "BR")
        val chosen = if (engine.isLanguageAvailable(ptBr) >= TextToSpeech.LANG_AVAILABLE) {
            ptBr
        } else {
            Locale.getDefault()
        }
        if (engine.setLanguage(chosen) < TextToSpeech.LANG_AVAILABLE) {
            engine.setLanguage(Locale.getDefault())
        }

        // Voz local do idioma escolhido, se houver (offline, sem rede).
        val voice = engine.voices
            ?.filter { it.locale.language == chosen.language }
            ?.sortedBy { if (it.isNetworkConnectionRequired) 1 else 0 }
            ?.firstOrNull()
        if (voice != null) engine.voice = voice

        // Timbre infantil: pitch acima do normal deixa a voz jovem/aguda. O TTS
        // nativo nao tem voz de crianca, entao este e o caminho possivel.
        engine.setPitch(CHILD_PITCH)
        engine.setSpeechRate(CHILD_RATE)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                AssistantStateBus.set(Phase.SPEAKING)
            }

            override fun onDone(utteranceId: String?) {
                AssistantStateBus.rest()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                AssistantStateBus.rest()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "TTS falhou (codigo $errorCode)")
                AssistantStateBus.rest()
            }
        })

        ttsReady = true
        pendingSpeech?.let(::speakNow)
        pendingSpeech = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_listening)))
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LISTEN -> runTurn()
            ACTION_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let(::runText)
            else -> startListening()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopListening()
        tts?.stop()
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Escuta continua ----

    private fun startListening() {
        listening = true
        if (wakeWord != null || busy) return
        scope.launch {
            try {
                brain.ensureLoaded()
            } catch (t: Throwable) {
                AssistantStateBus.base = Phase.IDLE
                AssistantStateBus.updateMessage("Falha ao carregar os modelos: ${t.message}")
                say("Falha ao carregar os modelos: ${t.message}")
                listening = false
                return@launch
            }
            AssistantStateBus.base = Phase.LISTENING
            AssistantStateBus.set(Phase.LISTENING)
            wakeWord = WhistleWakeWord().also { detector ->
                detector.start {
                    wakeWord = null
                    runTurn()
                }
            }
        }
    }

    private fun stopListening() {
        listening = false
        wakeWord?.stop()
        wakeWord = null
        AssistantStateBus.base = Phase.IDLE
        if (AssistantStateBus.phase.value == Phase.LISTENING) AssistantStateBus.set(Phase.IDLE)
    }

    // ---- Turnos ----

    private fun runTurn() {
        if (busy) return
        busy = true
        scope.launch {
            val hadWake = wakeWord != null
            wakeWord?.stop()
            wakeWord = null
            // O AudioRecord do wake word solta o mic alguns ms depois do cancel;
            // a folga evita abrir o turno em cima dele (gravava silencio).
            if (hadWake) delay(100)
            try {
                brain.ensureLoaded()
                AssistantStateBus.set(Phase.HEARING)
                val pcm = withContext(Dispatchers.IO) { capture.recordUtterance() }
                AssistantStateBus.set(Phase.THINKING)
                val turn = brain.handleAudio(pcm)
                announce(turn)
                say(turn.speak)
                updateNotification(turn.status)
            } catch (t: Throwable) {
                AssistantStateBus.flash(Phase.SAD)
                AssistantStateBus.updateMessage("Erro: ${t.message}")
                say("Erro: ${t.message}")
            } finally {
                busy = false
                if (listening) startListening()
            }
        }
    }

    private fun runText(text: String) {
        scope.launch {
            try {
                brain.ensureLoaded()
                AssistantStateBus.set(Phase.THINKING)
                val turn = brain.handleText(text)
                announce(turn)
                say(turn.speak)
                updateNotification(turn.status)
            } catch (t: Throwable) {
                AssistantStateBus.flash(Phase.SAD)
                AssistantStateBus.updateMessage("Erro: ${t.message}")
                say("Erro: ${t.message}")
            }
        }
    }

    /** Publica o resultado do turno no bus (texto + reacao do mascote). */
    private fun announce(turn: Turn) {
        AssistantStateBus.updateMessage(turn.status)
        when (turn.kind) {
            Turn.Kind.ACTED -> AssistantStateBus.flash(Phase.CELEBRATING)
            Turn.Kind.REFUSED, Turn.Kind.ERROR -> AssistantStateBus.flash(Phase.SAD)
            Turn.Kind.CONFIRM, Turn.Kind.ANSWER -> Unit
        }
    }

    // ---- Notificacao e voz ----

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(mainIntent())
            .setOngoing(true)
            .build()

    /** Tap na notificacao abre o app. */
    private fun mainIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun say(text: String) {
        if (text.isBlank()) return
        if (!ttsReady) {
            pendingSpeech = text
            return
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    companion object {
        private const val TAG = "CatarinaService"
        private const val CHANNEL = "catarina"
        private const val NOTIF_ID = 1
        private const val UTTERANCE_ID = "catarina"

        /** Pitch > 1 deixa o timbre jovem/agudo (o TTS nativo nao tem voz de crianca). */
        private const val CHILD_PITCH = 1.35f
        private const val CHILD_RATE = 1.02f

        const val ACTION_START = "dev.catarina.assistant.START"
        const val ACTION_STOP = "dev.catarina.assistant.STOP"
        const val ACTION_LISTEN = "dev.catarina.assistant.LISTEN"
        const val ACTION_TEXT = "dev.catarina.assistant.TEXT"
        const val EXTRA_TEXT = "text"

        fun start(context: Context) = context.startForegroundService(
            Intent(context, CatarinaService::class.java).setAction(ACTION_START),
        )

        fun stop(context: Context) =
            context.stopService(Intent(context, CatarinaService::class.java))

        fun listen(context: Context) = context.startForegroundService(
            Intent(context, CatarinaService::class.java).setAction(ACTION_LISTEN),
        )
    }
}
