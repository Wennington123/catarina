package dev.catarina.assistant.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Guarda os pesos (`.cact`). Este e o UNICO ponto de rede do app: acontece uma
 * vez, no primeiro uso, e depois tudo roda offline.
 */
class ModelRepository(private val context: Context) {

    data class Model(val name: String, val url: String, val sha256: String? = null)

    private val dir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    fun file(model: Model): File = File(dir, model.name)

    fun isReady(model: Model): Boolean = file(model).let { it.exists() && it.length() > 0L }

    /** Baixa com progresso e verifica o hash quando definido. Idempotente. */
    suspend fun ensure(
        model: Model,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val target = file(model)
        if (isReady(model)) return@withContext target

        val tmp = File(dir, "${model.name}.part")
        val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        conn.connect()
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode} ao baixar ${model.name}" }

        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        }

        model.sha256?.let { expected ->
            val actual = sha256(tmp)
            if (!actual.equals(expected, ignoreCase = true)) {
                tmp.delete()
                error("hash divergente em ${model.name}")
            }
        }

        check(tmp.renameTo(target)) { "nao consegui finalizar ${model.name}" }
        target
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val NEEDLE3_BASE = "https://huggingface.co/Cactus-Compute/needle3/resolve/main"
        private const val WHISTLE_BASE = "https://huggingface.co/Cactus-Compute/whistle/resolve/main"

        /** Pesos de tool calling / extracao. */
        val NEEDLE3 = Model("needle3.cact", "$NEEDLE3_BASE/needle3.cact")

        /** Pesos de STT (Whistle). */
        val WHISTLE = Model("whistle.cact", "$WHISTLE_BASE/whistle.cact")
    }
}
