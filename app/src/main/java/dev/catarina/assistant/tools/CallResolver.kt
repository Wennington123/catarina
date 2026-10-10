package dev.catarina.assistant.tools

import dev.catarina.assistant.engine.FunctionCall
import dev.catarina.assistant.engine.NeedleResponse

/**
 * Transforma a resposta crua do engine numa decisao. O smoke test no A32
 * mostrou que o modelo pode emitir chamadas duplicadas ou contraditorias
 * (sobretudo em portugues), entao aqui removemos as duplicatas exatas e, quando
 * a mesma ferramenta aparece com argumentos diferentes, fica so a primeira.
 *
 * **Nao existe confirmacao**: se o engine pediu uma ferramenta, ela executa.
 * Em portugues o head de confianca do Needle devolve 0.02-0.30, e exigir
 * confirmacao nesse cenario significava perguntar "Confirma?" para sempre e
 * nunca executar nada de verdade.
 */
object CallResolver {

    sealed interface Decision {
        /** Executar as chamadas. */
        data class Act(val calls: List<FunctionCall>) : Decision

        /** Nada a fazer (off-topic). */
        data object Refuse : Decision
    }

    fun resolve(response: NeedleResponse, allowed: Set<String>? = null): Decision =
        resolveCalls(response.functionCalls, response.suppressedCalls, allowed)

    /**
     * Mesma decisao, a partir das chamadas cruas. Usado tambem pelo cerebro
     * remoto (OpenAI-compatible), que nao produz um `NeedleResponse`.
     *
     * `allowed` restringe as ferramentas aceitas (no modo local, as que casaram
     * com algum `trigger` na fala); `null` nao filtra nada.
     */
    fun resolveCalls(
        calls: List<FunctionCall>,
        held: List<FunctionCall> = emptyList(),
        allowed: Set<String>? = null,
    ): Decision {
        val target = dedupe(calls).ifEmpty { dedupe(held) }
        val usable = if (allowed == null) target else target.filter { it.name in allowed }
        if (usable.isEmpty()) return Decision.Refuse
        return Decision.Act(onePerTool(usable))
    }

    private fun dedupe(calls: List<FunctionCall>): List<FunctionCall> =
        calls.distinctBy { it.name to it.arguments.toString() }

    /** Conflito (mesma ferramenta, argumentos diferentes) fica com a primeira. */
    private fun onePerTool(calls: List<FunctionCall>): List<FunctionCall> =
        calls.distinctBy { it.name }
}
