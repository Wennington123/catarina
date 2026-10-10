package dev.catarina.assistant.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

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

    /**
     * Modelo pt-BR do Vosk: baixa o zip (uma vez) e extrai em `models/vosk-pt/`.
     * Idempotente — se a pasta ja tem o `final.mdl`, nao baixa de novo.
     */
    suspend fun ensureVoskPt(onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): File {
        val target = File(dir, VOSK_PT_DIR)
        if (File(target, "final.mdl").exists()) return target

        val zip = ensure(VOSK_PT_ZIP, onProgress)
        return withContext(Dispatchers.IO) {
            extract(zip, target)
            zip.delete()
            target
        }
    }

    /**
     * Extrai o zip do Vosk descartando o primeiro nivel (`vosk-model-small-pt-0.3/`),
     * que e onde ficam `final.mdl`, `Gr.fst` e `ivector/`: o Vosk quer a pasta do
     * modelo direto. Entradas que escapariam do destino sao ignoradas (zip slip).
     */
    private fun extract(zip: File, dest: File) {
        dest.mkdirs()
        val guard = dest.canonicalPath + File.separator
        ZipFile(zip).use { zf ->
            for (entry in zf.entries()) {
                val parts = entry.name.split('/').drop(1).filter { it.isNotEmpty() }
                if (parts.isEmpty()) continue
                val out = File(dest, parts.joinToString("/"))
                if (!out.canonicalPath.startsWith(guard)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
        }
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
        private const val VOSK_BASE = "https://alphacephei.com/vosk/models"

        /** Pasta com o modelo pt-BR ja extraido, do jeito que o Vosk espera. */
        const val VOSK_PT_DIR = "vosk-pt"

        /** Pesos de tool calling / extracao. */
        val NEEDLE3 = Model("needle3.cact", "$NEEDLE3_BASE/needle3.cact")

        /** Pesos de STT (Whistle). */
        val WHISTLE = Model("whistle.cact", "$WHISTLE_BASE/whistle.cact")

        /**
         * Modelo pt-BR do Vosk (~31 MB): vem em zip e e extraido em [VOSK_PT_DIR].
         * O hash e do zip publicado em alphacephei.com/vosk/models.
         */
        val VOSK_PT_ZIP = Model(
            name = "vosk-model-small-pt-0.3.zip",
            url = "$VOSK_BASE/vosk-model-small-pt-0.3.zip",
            sha256 = "6e1ce909032e1afa7a88e68a3d628ecafff302bdf195befab308826c395e93b7",
        )
    }
}
