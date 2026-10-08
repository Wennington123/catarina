package dev.catarina.assistant.core

import android.content.Context
import dev.catarina.assistant.engine.NeedleEngine
import dev.catarina.assistant.engine.NeedleResponse
import dev.catarina.assistant.model.ModelRepository
import dev.catarina.assistant.tools.CallResolver
import dev.catarina.assistant.tools.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Dono unico do engine (process-global, nao thread-safe) e da conversa.
 * Compartilhado pelo `CatarinaService` e pela UI; o `Mutex` serializa os turnos.
 */
class CatarinaBrain(private val context: Context) {

    private val models = ModelRepository(context)
    private val executor = ToolExecutor(context)
    private val mutex = Mutex()

    @Volatile
    private var loaded = false

    /** Resultado de um turno: o que mostrar e o que falar. */
    data class Turn(val status: String, val speak: String, val kind: Kind) {
        enum class Kind { ACTED, CONFIRM, REFUSED, ERROR }
    }

    /** Baixa (1x) e carrega os modelos. Idempotente. */
    suspend fun ensureLoaded(onProgress: (downloaded: Long, total: Long, name: String) -> Unit = { _, _, _ -> }) =
        mutex.withLock {
            if (loaded) return@withLock
            withContext(Dispatchers.IO) {
                val needle = models.ensure(ModelRepository.NEEDLE3) { d, t -> onProgress(d, t, "needle3.cact") }
                val whistle = models.ensure(ModelRepository.WHISTLE) { d, t -> onProgress(d, t, "whistle.cact") }
                val tools = context.assets.open("tools.json").bufferedReader().use { it.readText() }
                NeedleEngine.load(needle)
                NeedleEngine.load(whistle)
                NeedleEngine.init(systemPrompt = SYSTEM, toolsJson = tools)
            }
            loaded = true
        }

    /** Um turno a partir de audio (o engine transcreve e responde na mesma chamada). */
    suspend fun handleAudio(pcm: FloatArray, floor: Double = FLOOR): Turn = mutex.withLock {
        if (pcm.isEmpty()) return@withLock Turn("Nao ouvi nada.", "", Turn.Kind.REFUSED)
        withContext(Dispatchers.IO) {
            val response = NeedleEngine.completeAudio(pcm)
                ?: return@withContext Turn("Falha no engine: ${NeedleEngine.lastError()}", "", Turn.Kind.ERROR)
            decide(response, floor)
        }
    }

    /** Um turno a partir de texto. */
    suspend fun handleText(text: String, floor: Double = FLOOR): Turn = mutex.withLock {
        withContext(Dispatchers.IO) {
            val response = NeedleEngine.completeText(text)
                ?: return@withContext Turn("Falha no engine: ${NeedleEngine.lastError()}", "", Turn.Kind.ERROR)
            decide(response, floor)
        }
    }

    suspend fun transcribe(pcm: FloatArray): String? = mutex.withLock {
        withContext(Dispatchers.IO) { NeedleEngine.transcribe(pcm) }
    }

    private fun decide(response: NeedleResponse, floor: Double): Turn =
        when (val decision = CallResolver.resolve(response, floor)) {
            is CallResolver.Decision.Refuse ->
                Turn("Nao consigo fazer isso.", "Nao consigo fazer isso.", Turn.Kind.REFUSED)

            is CallResolver.Decision.Confirm -> {
                val names = decision.calls.joinToString(", ") { it.name }
                Turn("Confirma? $names (${decision.reason})", "Confirma $names?", Turn.Kind.CONFIRM)
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

    private companion object {
        const val FLOOR = 0.7
        const val SYSTEM = "device: phone; locale: pt-BR"
    }
}
