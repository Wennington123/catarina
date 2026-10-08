package dev.catarina.assistant.engine

import org.json.JSONArray
import org.json.JSONObject

/** Uma chamada de ferramenta escolhida pelo modelo. */
data class FunctionCall(val name: String, val arguments: JSONObject)

/**
 * Resposta de um turno do engine. Cada turno devolve um unico objeto JSON:
 * `type`, `function_calls`, `suppressed_calls`, `reasoning`, `confidence`,
 * `prefill_tps`, `decode_tps`, `peak_ram_mb`.
 */
data class NeedleResponse(
    val type: String,
    val success: Boolean,
    val functionCalls: List<FunctionCall>,
    val suppressedCalls: List<FunctionCall>,
    val reasoning: String?,
    val confidence: Double?,
    val prefillTps: Double?,
    val decodeTps: Double?,
    val peakRamMb: Double?,
    val raw: String,
) {
    /** Recusa: nada a fazer (off-topic), nem chamada retida. */
    val isRefusal: Boolean get() = functionCalls.isEmpty() && suppressedCalls.isEmpty()

    val bestCalls: List<FunctionCall> get() = functionCalls.ifEmpty { suppressedCalls }

    companion object {
        fun parse(json: String): NeedleResponse {
            val o = JSONObject(json)
            return NeedleResponse(
                type = o.optString("type", "respond"),
                success = o.optBoolean("success", o.optInt("error_code", 0) == 0),
                functionCalls = calls(o.optJSONArray("function_calls")),
                suppressedCalls = calls(o.optJSONArray("suppressed_calls")),
                reasoning = o.optString("reasoning").ifEmpty { null },
                confidence = o.optDoubleOrNull("confidence"),
                prefillTps = o.optDoubleOrNull("prefill_tps"),
                decodeTps = o.optDoubleOrNull("decode_tps"),
                peakRamMb = o.optDoubleOrNull("peak_ram_mb"),
                raw = json,
            )
        }

        private fun calls(arr: JSONArray?): List<FunctionCall> {
            if (arr == null) return emptyList()
            return buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val name = c.optString("name")
                    if (name.isEmpty()) continue
                    add(FunctionCall(name, argsOf(c.opt("arguments"))))
                }
            }
        }

        // `arguments` normalmente e um objeto, mas aceita string JSON tambem.
        private fun argsOf(v: Any?): JSONObject = when (v) {
            is JSONObject -> v
            is String -> runCatching { JSONObject(v) }.getOrElse { JSONObject() }
            else -> JSONObject()
        }

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
    }
}
