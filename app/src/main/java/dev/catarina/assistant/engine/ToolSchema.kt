package dev.catarina.assistant.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converte o `assets/tools.json` (formato do Needle) na lista de `tools` do
 * protocolo OpenAI (function calling). Assim o provedor remoto e o modo local
 * usam **o mesmo** conjunto de ferramentas — nada duplica.
 *
 * `triggers` e heuristica local do Needle (rota por regex) e nao vai pro
 * provedor, que roteia sozinho pelas `description`.
 */
object ToolSchema {

    fun toOpenAi(toolsJson: String): JSONArray {
        val src = JSONArray(toolsJson)
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val t = src.optJSONObject(i) ?: continue
            val name = t.optString("name")
            if (name.isEmpty()) continue

            val function = JSONObject()
                .put("name", name)
                .put("description", t.optString("description"))
                .put("parameters", t.optJSONObject("parameters") ?: emptyObject())

            out.put(JSONObject().put("type", "function").put("function", function))
        }
        return out
    }

    private fun emptyObject(): JSONObject =
        JSONObject().put("type", "object").put("properties", JSONObject())
}
