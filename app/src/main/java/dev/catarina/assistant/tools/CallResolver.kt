package dev.catarina.assistant.tools

import dev.catarina.assistant.engine.FunctionCall
import dev.catarina.assistant.engine.NeedleResponse

/**
 * Transforma a resposta crua do engine numa decisao. O smoke test no A32
 * mostrou que o modelo pode emitir chamadas duplicadas ou contraditorias
 * (sobretudo em portugues), entao aqui:
 *
 * - removemos duplicatas exatas;
 * - detectamos conflito (mesma ferramenta, argumentos diferentes);
 * - nunca executamos acao sensivel sem confirmacao;
 * - aplicamos o piso de confianca.
 *
 * Nota: o fine-tune LOCAL nao treina o head de confianca, entao `confidence`
 * vem `None`. Nesse caso agimos (o modelo foi treinado para essas tools), mas
 * acoes sensiveis seguem exigindo confirmacao.
 */
object CallResolver {

    /** Acoes que nunca executam sem confirmacao, qualquer que seja a confianca. */
    private val SENSITIVE = setOf("send_sms", "run_shell", "set_ac")

    sealed interface Decision {
        /** Executar as chamadas. */
        data class Act(val calls: List<FunctionCall>) : Decision

        /** Baixa confianca, conflito ou acao sensivel: mostrar e pedir confirmacao. */
        data class Confirm(val calls: List<FunctionCall>, val reason: String) : Decision

        /** Nada a fazer (off-topic). */
        data object Refuse : Decision
    }

    fun resolve(response: NeedleResponse, floor: Double = 0.7): Decision {
        val calls = dedupe(response.functionCalls)
        val held = dedupe(response.suppressedCalls)

        if (calls.isEmpty() && held.isEmpty()) return Decision.Refuse

        val target = calls.ifEmpty { held }
        val confidence = response.confidence

        return when {
            hasConflict(target) -> Decision.Confirm(target, "chamadas conflitantes")

            target.any { it.name in SENSITIVE } -> Decision.Confirm(target, "acao sensivel")

            calls.isNotEmpty() && (confidence == null || confidence >= floor) -> Decision.Act(calls)

            else -> Decision.Confirm(
                target,
                "confianca ${"%.2f".format(confidence ?: 0.0)} < $floor",
            )
        }
    }

    private fun dedupe(calls: List<FunctionCall>): List<FunctionCall> =
        calls.distinctBy { it.name to it.arguments.toString() }

    /** Conflito = mesma ferramenta com argumentos diferentes. */
    private fun hasConflict(calls: List<FunctionCall>): Boolean =
        calls.groupBy { it.name }.any { (_, group) -> group.map { it.arguments.toString() }.distinct().size > 1 }
}
