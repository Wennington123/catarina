package dev.catarina.assistant.core

import android.content.Context
import dev.catarina.assistant.engine.FunctionCall
import dev.catarina.assistant.engine.NeedleEngine
import dev.catarina.assistant.engine.NeedleResponse
import dev.catarina.assistant.model.ModelRepository
import dev.catarina.assistant.state.AppSettings
import dev.catarina.assistant.state.AssistantStateBus
import dev.catarina.assistant.state.Phase
import dev.catarina.assistant.tools.CallResolver
import dev.catarina.assistant.tools.ToolExecutor
import java.text.Normalizer
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

    /**
     * Chamadas esperando confirmacao. Quando o gating devolve `Confirm`, elas
     * ficam guardadas aqui: o proximo turno que for um "sim" executa direto,
     * sem passar pelo engine. Sem isso, todo comando em portugues (confianca
     * 0.02-0.30, abaixo do piso) ficava eternamente pedindo confirmacao e nada
     * era executado.
     */
    private var pending: List<FunctionCall>? = null

    @Volatile
    private var loaded = false

    /** Resultado de um turno: o que mostrar e o que falar. */
    data class Turn(val status: String, val speak: String, val kind: Kind) {
        enum class Kind { ACTED, CONFIRM, REFUSED, ERROR, ANSWER }
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
    suspend fun handleAudio(pcm: FloatArray, floor: Double = FLOOR): Turn {
        if (pcm.isEmpty()) return Turn("Nao ouvi nada.", "", Turn.Kind.REFUSED)
        if (AppSettings.providerRemote) {
            val text = transcribeText(pcm)
                ?: return Turn("Nao entendi o audio.", "Nao entendi.", Turn.Kind.REFUSED)
            return handleText(text, floor)
        }
        // No modo local o audio entra direto no engine, que nao devolve texto.
        // Com confirmacao pendente, transcrevemos so pra ler o "sim"/"nao".
        if (pending != null) {
            val text = transcribeText(pcm)
            if (text != null) {
                val resolved = resolvePending(text)
                if (resolved != null) return resolved
            }
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val response = NeedleEngine.completeAudio(pcm)
                    ?: return@withContext Turn("Falha no engine: ${NeedleEngine.lastError()}", "", Turn.Kind.ERROR)
                decide(response, floor)
            }
        }
    }

    /** Um turno a partir de texto. */
    suspend fun handleText(text: String, floor: Double = FLOOR): Turn {
        resolvePending(text)?.let { return it }
        if (AppSettings.providerRemote) {
            if (text.isBlank()) return Turn("Nao entendi.", "", Turn.Kind.REFUSED)
            return withContext(Dispatchers.IO) { remote.complete(text) }
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val response = NeedleEngine.completeText(text)
                    ?: return@withContext Turn("Falha no engine: ${NeedleEngine.lastError()}", "", Turn.Kind.ERROR)
                decide(response, floor)
            }
        }
    }

    suspend fun transcribe(pcm: FloatArray): String? = mutex.withLock {
        withContext(Dispatchers.IO) { NeedleEngine.transcribe(pcm) }
    }

    /** Texto puro do Whistle (STT local do modo remoto). */
    private suspend fun transcribeText(pcm: FloatArray): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val json = NeedleEngine.transcribe(pcm) ?: return@withContext null
            runCatching { JSONObject(json).optString("text") }
                .getOrNull()
                ?.trim()
                ?.ifEmpty { null }
        }
    }

    private fun decide(response: NeedleResponse, floor: Double): Turn =
        when (val decision = CallResolver.resolve(response, floor)) {
            is CallResolver.Decision.Refuse ->
                Turn("Nao consigo fazer isso.", "Nao consigo fazer isso.", Turn.Kind.REFUSED)

            is CallResolver.Decision.Confirm -> {
                pending = decision.calls
                val names = decision.calls.joinToString(", ") { it.name }
                Turn(
                    status = "Confirma? $names (${decision.reason}) — diga \"sim\" ou \"nao\"",
                    speak = "Confirma $names?",
                    kind = Turn.Kind.CONFIRM,
                )
            }

            is CallResolver.Decision.Act -> {
                val results = decision.calls.map { executor.execute(it) }
                Turn(
                    status = results.joinToString("\n") { it.detail },
                    speak = results.joinToString(", ") { it.detail },
                    kind = Turn.Kind.ACTED,
                )
            }
        }

    // ---- Confirmacao pendente ----

    private enum class Answer { YES, NO, OTHER }

    /**
     * Com confirmacao pendente, o turno e decidido so pelo que foi dito — sem
     * engine: "sim" executa as chamadas guardadas, "nao" descarta. Qualquer
     * outra coisa descarta a confirmacao e segue o fluxo normal (devolve null).
     */
    private fun resolvePending(text: String): Turn? {
        val calls = pending ?: return null
        return when (classify(text)) {
            Answer.YES -> {
                pending = null
                val results = calls.map { executor.execute(it) }
                Turn(
                    status = results.joinToString("\n") { it.detail },
                    speak = results.joinToString(", ") { it.detail },
                    kind = Turn.Kind.ACTED,
                )
            }

            Answer.NO -> {
                pending = null
                Turn("Cancelado.", "Deixa pra la.", Turn.Kind.REFUSED)
            }

            Answer.OTHER -> {
                pending = null
                null
            }
        }
    }

    /**
     * So conta como resposta se a fala for curta (ate [MAX_ANSWER_WORDS]
     * palavras): senao "liga a lanterna no modo silencioso" seria lido como uma
     * confirmacao da acao anterior.
     */
    private fun classify(text: String): Answer {
        val words = normalize(text).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > MAX_ANSWER_WORDS) return Answer.OTHER
        if (words.any { it in NO_WORDS }) return Answer.NO
        if (words.any { it in YES_WORDS }) return Answer.YES
        return Answer.OTHER
    }

    /** Minusculas e sem acento: "Sim!" e "não" casam com as listas. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private companion object {
        const val FLOOR = 0.7
        const val SYSTEM = "device: phone; locale: pt-BR"

        /** Fala mais longa que isso e comando novo, nao resposta. */
        const val MAX_ANSWER_WORDS = 3

        val YES_WORDS = setOf(
            "sim", "confirma", "confirmo", "pode", "isso", "claro", "beleza",
            "ok", "manda", "vai", "faz", "yes",
        )

        val NO_WORDS = setOf(
            "nao", "cancela", "cancelar", "negativo", "deixa", "espera", "pare",
        )
    }
}
