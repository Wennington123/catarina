package dev.catarina.assistant.core

import dev.catarina.assistant.engine.FunctionCall
import dev.catarina.assistant.engine.ToolSchema
import dev.catarina.assistant.state.AppSettings
import dev.catarina.assistant.tools.CallResolver
import dev.catarina.assistant.tools.ToolExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cerebro remoto: fala com um endpoint **OpenAI-compatible** (chat completions
 * + function calling) e reusa o MESMO `tools.json` e o mesmo `CallResolver`/
 * `ToolExecutor` do modo local.
 *
 * O STT continua local (Whistle): so o texto vai pra nuvem. O ganho sobre o
 * Needle e conversa natural, portugues bom sem fine-tune e memoria multi-turn;
 * as ferramentas seguem no aparelho.
 *
 * Fluxo de tool call: manda as tools, se o modelo pedir uma chamada o app
 * executa e devolve o resultado (`role: tool`), e so entao o modelo produz a
 * resposta em linguagem natural que a Catarina fala.
 */
class RemoteBrain(
    private val executor: ToolExecutor,
    toolsJson: String,
) {
    private val tools: JSONArray = ToolSchema.toOpenAi(toolsJson)
    private val history = ArrayList<JSONObject>()

    fun reset() = history.clear()

    fun complete(text: String): CatarinaBrain.Turn {
        if (!AppSettings.providerConfigured) {
            return CatarinaBrain.Turn(
                "Provedor nao configurado: preencha URL e modelo nas Configuracoes.",
                "",
                CatarinaBrain.Turn.Kind.ERROR,
            )
        }

        seed()
        history.add(message("user", text))

        var round = 0
        while (round++ < MAX_ROUNDS) {
            trim()
            val (code, raw) = postJson("/chat/completions", chatBody())
            if (code !in 200..299) {
                return CatarinaBrain.Turn(
                    "Falha no provedor (HTTP $code): ${brief(raw)}",
                    "Nao consegui falar com o provedor.",
                    CatarinaBrain.Turn.Kind.ERROR,
                )
            }

            val reply = runCatching {
                JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            }.getOrNull() ?: return CatarinaBrain.Turn(
                "Resposta inesperada do provedor.",
                "",
                CatarinaBrain.Turn.Kind.ERROR,
            )

            val toolCalls = toolCallsOf(reply)
            if (toolCalls.isNotEmpty()) {
                when (val decision = CallResolver.resolveCalls(toolCalls.map { it.second })) {
                    is CallResolver.Decision.Refuse ->
                        return CatarinaBrain.Turn(
                            "Nao consigo fazer isso.",
                            "Nao consigo fazer isso.",
                            CatarinaBrain.Turn.Kind.REFUSED,
                        )

                    is CallResolver.Decision.Confirm -> {
                        val names = decision.calls.joinToString(", ") { it.name }
                        return CatarinaBrain.Turn(
                            "Confirma? $names (${decision.reason})",
                            "Confirma $names?",
                            CatarinaBrain.Turn.Kind.CONFIRM,
                        )
                    }

                    is CallResolver.Decision.Act -> {
                        history.add(reply)
                        for ((id, call) in toolCalls) {
                            val result = executor.execute(call)
                            history.add(
                                JSONObject()
                                    .put("role", "tool")
                                    .put("tool_call_id", id)
                                    .put("content", result.toJson()),
                            )
                        }
                        continue
                    }
                }
            }

            val content = reply.optString("content").trim()
            history.add(message("assistant", content))
            return if (content.isEmpty()) {
                CatarinaBrain.Turn("Nao entendi.", "", CatarinaBrain.Turn.Kind.REFUSED)
            } else {
                CatarinaBrain.Turn(content, content, CatarinaBrain.Turn.Kind.ANSWER)
            }
        }

        return CatarinaBrain.Turn("Nao consegui concluir o pedido.", "", CatarinaBrain.Turn.Kind.REFUSED)
    }

    // ---- Protocolo ----

    private fun chatBody(): JSONObject = JSONObject()
        .put("model", AppSettings.providerModel)
        .put("messages", JSONArray(history))
        .put("tools", tools)
        .put("tool_choice", "auto")
        .put("max_tokens", MAX_TOKENS)

    private fun toolCallsOf(message: JSONObject): List<Pair<String, FunctionCall>> {
        val arr = message.optJSONArray("tool_calls") ?: return emptyList()
        val out = ArrayList<Pair<String, FunctionCall>>(arr.length())
        for (i in 0 until arr.length()) {
            val tc = arr.optJSONObject(i) ?: continue
            val fn = tc.optJSONObject("function") ?: continue
            val name = fn.optString("name")
            if (name.isEmpty()) continue
            val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }
                .getOrElse { JSONObject() }
            out.add(tc.optString("id") to FunctionCall(name, args))
        }
        return out
    }

    // ---- Historico ----

    private fun seed() {
        if (history.isEmpty()) history.add(message("system", SYSTEM))
    }

    /** Mantem o system prompt (indice 0) e descarta o turno mais antigo. */
    private fun trim() {
        while (history.size > MAX_HISTORY) history.removeAt(1)
    }

    private fun message(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    companion object {
        /**
         * Prova de vida do provedor (uma requisicao minima), sem instanciar o
         * cerebro. Bloqueante: chamar fora da main thread.
         */
        fun testConnection(): String {
            if (!AppSettings.providerConfigured) return "Preencha URL e modelo"
            val body = JSONObject()
                .put("model", AppSettings.providerModel)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "ping")),
                )
                .put("max_tokens", 8)
            val (code, raw) = postJson("/chat/completions", body)
            return if (code in 200..299) "OK (HTTP $code)" else "Falhou (HTTP $code): ${brief(raw)}"
        }

        private const val MAX_ROUNDS = 3
        private const val MAX_HISTORY = 24
        private const val MAX_TOKENS = 512
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val SYSTEM =
            "Voce e a Catarina, assistente de voz infantil em portugues do Brasil. " +
                "Responda curto e natural (1 a 2 frases), com tom amigavel. " +
                "Quando o pedido for sobre o aparelho (wifi, bluetooth, lanterna, brilho, volume, " +
                "abrir app, sms, notificacao), use as ferramentas. " +
                "Se algo falhar, explique em uma frase simples."
    }
}

/** POST JSON no endpoint OpenAI-compatible. Devolve (status HTTP, corpo). */
private fun postJson(path: String, body: JSONObject): Pair<Int, String> {
    val base = AppSettings.providerBaseUrl.trim().trimEnd('/')
    val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 20_000
        readTimeout = 60_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val key = AppSettings.providerApiKey.trim()
        if (key.isNotEmpty()) setRequestProperty("Authorization", "Bearer $key")
    }
    return try {
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        code to text
    } catch (t: Throwable) {
        -1 to (t.message ?: t.toString())
    } finally {
        conn.disconnect()
    }
}

private fun brief(text: String): String = text.trim().replace('\n', ' ').take(160)
