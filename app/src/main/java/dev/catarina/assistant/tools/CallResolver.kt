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
 * - aplicamos o piso de confianca, pedindo confirmacao quando nao da pra agir.
 */
object CallResolver {

    sealed interface Decision {
        /** Executar as chamadas. */
        data class Act(val calls: List<FunctionCall>) : Decision

        /** Baixa confianca ou conflito: mostrar e pedir confirmacao. */
        data class Confirm(val calls: List<FunctionCall>, val reason: String) : Decision

        /** Nada a fazer (off-topic). */
        data object Refuse : Decision
    }

    fun resolve(response: NeedleResponse, floor: Double = 0.7): Decision {
        val calls = dedupe(response.functionCalls)
        val held = dedupe(response.suppressedCalls)

        if (calls.isEmpty() && held.isEmpty()) return Decision.Refuse

        val conflict = hasConflict(calls) || hasConflict(held)
        val confident = (response.confidence ?: 0.0) >= floor

        return if (calls.isNotEmpty() && confident && !conflict) {
            Decision.Act(calls)
        } else {
            val reason = when {
                conflict -> "chamadas conflitantes"
                else -> "confianca ${"%.2f".format(response.confidence ?: 0.0)} < $floor"
            }
            Decision.Confirm(calls.ifEmpty { held }, reason)
        }
    }

    private fun dedupe(calls: List<FunctionCall>): List<FunctionCall> =
        calls.distinctBy { it.name to it.arguments.toString() }

    /** Conflito = mesma ferramenta com argumentos diferentes. */
    private fun hasConflict(calls: List<FunctionCall>): Boolean =
        calls.groupBy { it.name }.any { (_, group) -> group.map { it.arguments.toString() }.distinct().size > 1 }
}
