package dev.catarina.assistant.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fases visiveis do assistente — sao elas que dirigem o mascote:
 *
 *  IDLE         repouso (service desligado)
 *  BOOTING      baixando/carregando os pesos (com progresso)
 *  LISTENING    wake word armada (modo continuo)
 *  HEARING      usuario falando (gravando o utterance)
 *  THINKING     engine processando
 *  SPEAKING     TTS falando
 *  CELEBRATING  acao executada com sucesso (transitorio)
 *  SAD          recusa/erro (transitorio)
 */
enum class Phase { IDLE, BOOTING, LISTENING, HEARING, THINKING, SPEAKING, CELEBRATING, SAD }

/**
 * Estado observavel compartilhado por UI, service, tile e bolha (mesmo
 * processo, singleton basta). `base` e a fase de repouso; `flash` aplica
 * uma fase transitoria e volta para `base`.
 */
object AssistantStateBus {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** 0..1 durante o BOOTING (download dos pesos); null fora dele. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Fase de repouso: LISTENING no modo continuo, IDLE caso contrario. */
    @Volatile
    var base: Phase = Phase.IDLE

    private var revert: Job? = null

    /** Entra numa fase estavel (cancela qualquer flash pendente). */
    fun set(p: Phase) {
        revert?.cancel()
        revert = null
        _phase.value = p
    }

    /** Fase transitoria (celebrar/triste) com volta automatica ao repouso. */
    fun flash(p: Phase, ms: Long = 1500L) {
        revert?.cancel()
        _phase.value = p
        revert = scope.launch {
            delay(ms)
            _phase.value = base
        }
    }

    /** Volta ao repouso (fim de turno/fala). */
    fun rest() = set(base)

    fun updateProgress(value: Float?) {
        _progress.value = value
    }

    fun updateMessage(text: String?) {
        _message.value = text
    }
}
