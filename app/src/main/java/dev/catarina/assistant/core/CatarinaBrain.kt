package dev.catarina.assistant.core

import android.content.Context
import dev.catarina.assistant.engine.NeedleEngine
import dev.catarina.assistant.engine.NeedleResponse
import dev.catarina.assistant.engine.ToolTriggers
import dev.catarina.assistant.model.ModelRepository
import dev.catarina.assistant.state.AppSettings
import dev.catarina.assistant.state.AssistantStateBus
import dev.catarina.assistant.state.Phase
import dev.catarina.assistant.tools.CallResolver
import dev.catarina.assistant.tools.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Dono unico do engine (process-global, nao thread-safe) e da conversa.
 * Compartilhado pelo `CatarinaService` e pela UI; o `Mutex` serializa os turnos.
 */
class CatarinaBrain(private val context: Context) {

    private val models = ModelRepository(context)
    private val executor = ToolExecutor(context)
    private val mutex = Mutex()

    /** Tools cruas do `assets/tools.json` — compartilhadas pelo modo local e remoto. */
    private val toolsJson: String by lazy {
        context.assets.open("tools.json").bufferedReader().use { it.readText() }
    }

    /** Cerebro remoto (OpenAI-compatible); construido sob demanda. */
    private val remote: RemoteBrain by lazy { RemoteBrain(executor, toolsJson) }

    /** Regex por ferramenta — decide se o pedido falado e um comando conhecido. */
    private val triggers by lazy { ToolTriggers.parse(toolsJson) }

    @Volatile
    private var loaded = false

    /** Resultado de um turno: o que mostrar e o que falar. */
    data class Turn(val status: String, val speak: String, val kind: Kind) {
        enum class Kind { ACTED, REFUSED, ERROR, ANSWER }
    }

    /** Baixa (1x) e carrega os modelos. Idempotente. Publica BOOTING + progresso. */
    suspend fun ensureLoaded(onProgress: (downloaded: Long, total: Long, name: String) -> Unit = { _, _, _ -> }) =
        mutex.withLock {
            if (loaded) return@withLock
            AssistantStateBus.set(Phase.BOOTING)
            try {
                withContext(Dispatchers.IO) {
                    val needle = models.ensure(ModelRepository.NEEDLE3) { d, t ->
                        onProgress(d, t, "needle3.cact")
                        AssistantStateBus.updateProgress(if (t > 0) d.toFloat() / t else null)
                    }
                    val whistle = models.ensure(ModelRepository.WHISTLE) { d, t ->
                        onProgress(d, t, "whistle.cact")
                        AssistantStateBus.updateProgress(if (t > 0) d.toFloat() / t else null)
                    }
                    val tools = toolsJson
                    NeedleEngine.load(needle)
                    NeedleEngine.load(whistle)
                    NeedleEngine.init(systemPrompt = SYSTEM, toolsJson = tools)
                    // O Whistle so entende en/de/fr/es/it/nl/pl — **nao tem
                    // portugues** — e antes o idioma nunca era setado (ficava na
                    // deteccao automatica entre esses 7). Fixamos "en" para o
                    // caminho do engine; voz em PT vai pelo STT do Android
                    // (AppSettings.androidStt).
                    NeedleEngine.setAudio(language = "en")
                }
            } finally {
                AssistantStateBus.updateProgress(null)
            }
            loaded = true
        }

    /**
     * Um turno a partir de audio. No modo local o proprio engine transcreve e
     * responde na mesma chamada; no remoto o STT roda local (Whistle) e so o
     * texto vai pro provedor.
     */
    suspend fun handleAudio(pcm: FloatArray): Turn {
        if (pcm.isEmpty()) return Turn("Nao ouvi nada.", "", Turn.Kind.REFUSED)
        if (AppSettings.providerRemote) {
            val text = transcribeText(pcm)
                ?: return Turn("Nao entendi o audio.", "Nao entendi.", Turn.Kind.REFUSED)
            return handleText(text)
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                // Transcreve so pra julgar o pedido: o engine emite alguma
                // chamada quase sempre e, quando nada casa, chuta a ferramenta
                // mais generica (era o "tudo vira brilho 100%").
                val heard = NeedleEngine.transcribe(pcm)?.let(::transcriptOf)
                val allowed = heard?.let { ToolTriggers.matching(triggers, it) }
                if (allowed != null && allowed.isEmpty()) return@withContext UNKNOWN
                val response = NeedleEngine.completeAudio(pcm)
                    ?: return@withContext Turn("Falha no engine: ${NeedleEngine.lastError()}", "", Turn.Kind.ERROR)
                decide(response, allowed)
            }
        }
    }

    /** Um turno a partir de texto. */
    suspend fun handleText(text: String): Turn {
        if (AppSettings.providerRemote) {
            if (text.isBlank()) return Turn("Nao entendi.", "", Turn.Kind.REFUSED)
            return withContext(Dispatchers.IO) { remote.complete(text) }
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val allowed = ToolTriggers.matching(triggers, text)
                if (allowed.isEmpty()) return@withContext UNKNOWN
                val response = NeedleEngine.completeText(text)
                    ?: return@withContext Turn("Falha no engine: ${NeedleEngine.lastError()}", "", Turn.Kind.ERROR)
                decide(response, allowed)
            }
        }
    }

    suspend fun transcribe(pcm: FloatArray): String? = mutex.withLock {
        withContext(Dispatchers.IO) { NeedleEngine.transcribe(pcm) }
    }

    /** Texto puro do Whistle (STT local do modo remoto). */
    private suspend fun transcribeText(pcm: FloatArray): String? = mutex.withLock {
        withContext(Dispatchers.IO) { NeedleEngine.transcribe(pcm)?.let(::transcriptOf) }
    }

    /** Campo `text` da resposta do Whistle. */
    private fun transcriptOf(json: String): String? =
        runCatching { JSONObject(json).optString("text").trim() }.getOrNull()?.ifEmpty { null }

    private fun decide(response: NeedleResponse, allowed: Set<String>? = null): Turn =
        when (val decision = CallResolver.resolve(response, allowed)) {
            is CallResolver.Decision.Refuse -> UNKNOWN

            is CallResolver.Decision.Act -> {
                val results = decision.calls.map { executor.execute(it) }
                Turn(
                    status = results.joinToString("\n") { it.detail },
                    speak = results.joinToString(", ") { it.detail },
                    kind = Turn.Kind.ACTED,
                )
            }
        }

    /** Pedido que nao casa com nenhum comando conhecido. */
    private val UNKNOWN = Turn(
        "Nao conheco esse comando. Toque em \"O que ela faz\" pra ver o que eu entendo.",
        "Nao conheco esse comando.",
        Turn.Kind.REFUSED,
    )

    private companion object {
        const val SYSTEM = "device: phone; locale: pt-BR"
    }
}
