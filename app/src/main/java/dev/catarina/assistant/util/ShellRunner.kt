package dev.catarina.assistant.util

import java.util.concurrent.TimeUnit

/**
 * Executa comandos shell, como o app (sem root) ou via `su` (Magisk), quando
 * disponivel. Usado pelas ferramentas que precisam de privilegio (Wi-Fi,
 * Bluetooth, brilho) e pelo `run_shell`.
 */
object ShellRunner {

    data class Result(val ok: Boolean, val output: String)

    /** Detecta root uma vez (Magisk responde a `su -c id`). */
    val hasRoot: Boolean by lazy { run(listOf("id"), asRoot = true).ok }

    fun run(command: List<String>, asRoot: Boolean = false): Result = try {
        val cmd = if (asRoot) listOf("su", "-c", command.joinToString(" ")) else command
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            Result(process.exitValue() == 0, output.trim())
        } else {
            process.destroyForcibly()
            Result(false, "timeout")
        }
    } catch (t: Throwable) {
        Result(false, t.message ?: "falha ao executar")
    }

    private const val TIMEOUT_S = 10L
}
