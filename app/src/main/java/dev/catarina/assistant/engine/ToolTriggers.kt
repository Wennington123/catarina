package dev.catarina.assistant.engine

import org.json.JSONArray

/**
 * Indice dos `triggers` do `tools.json`: regex por ferramenta.
 *
 * Serve pra julgar o pedido **antes** de executar. O Needle 3 e English-centric
 * e em portugues devolve confianca perto de zero; quando nada casa, ele chuta a
 * ferramenta mais generica (`set_brightness`, de parametro unico) — foi o "tudo
 * vira brilho 100%". Os `triggers` sao a unica parte do roteamento escrita a mao
 * e testada no aparelho, entao sao eles que decidem se o pedido e reconhecido.
 */
object ToolTriggers {

    /** `tools.json` -> ferramenta por regex. Regex invalida e ignorada. */
    fun parse(toolsJson: String): Map<String, List<Regex>> {
        val src = runCatching { JSONArray(toolsJson) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, List<Regex>>()
        for (i in 0 until src.length()) {
            val tool = src.optJSONObject(i) ?: continue
            val name = tool.optString("name")
            if (name.isEmpty()) continue
            val patterns = tool.optJSONArray("triggers") ?: continue
            val regexes = buildList(patterns.length()) {
                for (j in 0 until patterns.length()) {
                    val pattern = patterns.optString(j)
                    if (pattern.isEmpty()) continue
                    runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()?.let(::add)
                }
            }
            if (regexes.isNotEmpty()) out[name] = regexes
        }
        return out
    }

    /** Ferramentas cujo trigger casa com a fala. Vazio = pedido nao reconhecido. */
    fun matching(index: Map<String, List<Regex>>, text: String): Set<String> {
        val said = text.trim()
        if (said.isEmpty()) return emptySet()
        return index.filterValues { regexes -> regexes.any { it.containsMatchIn(said) } }.keys
    }
}
