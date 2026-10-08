package dev.catarina.assistant.engine

import java.io.File

/**
 * Wrapper fino sobre o engine nativo do Needle/Whistle
 * (`libcatarina_jni.so`, que linka a `libneedle.a` estatica).
 *
 * O engine e process-global e NAO thread-safe, entao TODAS as chamadas sao
 * serializadas aqui pelo mesmo lock. Nao chame a C API por outros caminhos.
 */
object NeedleEngine {

    private val LOCK = Any()

    init {
        System.loadLibrary("catarina_jni")
        // Antes de qualquer carga de modelo: o engine pre-compilado tem
        // telemetria ligada por padrao.
        nativeDisableTelemetry()
    }

    // ---- JNI ----
    private external fun nativeDisableTelemetry()
    private external fun nativeLoad(cact: ByteArray): Int
    private external fun nativeModels(): Int
    private external fun nativeLastError(): String
    private external fun nativeInit(systemPrompt: String?, toolsJson: String?, toolIndexPath: String?): Int
    private external fun nativeCompleteText(input: String, maxNewTokens: Int, outCapacity: Int): String?
    private external fun nativeCompletePcm(pcm: FloatArray, maxNewTokens: Int, outCapacity: Int): String?
    private external fun nativeSetAudio(language: String?, keywords: String?, wordTimestamps: Boolean)
    private external fun nativeReset()
    private external fun nativeTranscribe(
        pcm: FloatArray,
        language: String?,
        keywords: String?,
        wordTimestamps: Boolean,
        outCapacity: Int,
    ): String?

    private external fun nativeStreamProcess(
        pcm: FloatArray,
        language: String?,
        keywords: String?,
        outCapacity: Int,
    ): String?

    private external fun nativeStreamStop(outCapacity: Int): String?

    private external fun nativeEmbedText(input: String, outCapacity: Int): FloatArray?

    // ---- API de alto nivel (serializada) ----

    private const val DEFAULT_OUT_CAPACITY = 65536
    private const val DEFAULT_MAX_NEW_TOKENS = 512

    /** Carrega um `.cact` (texto ou fala) lido do disco. */
    fun load(weights: File) = synchronized(LOCK) {
        val rc = nativeLoad(weights.readBytes())
        check(rc >= 0) { "needle_load falhou (rc=$rc): ${lastError()}" }
    }

    /** Registra system prompt e tools. Devolve o tamanho do prefixo tokenizado. */
    fun init(systemPrompt: String?, toolsJson: String?, toolIndexPath: String? = null): Int =
        synchronized(LOCK) {
            val rc = nativeInit(systemPrompt, toolsJson, toolIndexPath)
            check(rc >= 0) { "needle_init falhou (rc=$rc): ${lastError()}" }
            rc
        }

    fun models(): Int = synchronized(LOCK) { nativeModels() }

    fun completeText(
        input: String,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
        outCapacity: Int = DEFAULT_OUT_CAPACITY,
    ): NeedleResponse? = synchronized(LOCK) {
        nativeCompleteText(input, maxNewTokens, outCapacity)?.let(NeedleResponse::parse)
    }

    fun completeAudio(
        pcm: FloatArray,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
        outCapacity: Int = DEFAULT_OUT_CAPACITY,
    ): NeedleResponse? = synchronized(LOCK) {
        nativeCompletePcm(pcm, maxNewTokens, outCapacity)?.let(NeedleResponse::parse)
    }

    fun transcribe(
        pcm: FloatArray,
        language: String? = null,
        keywords: String? = null,
        wordTimestamps: Boolean = false,
        outCapacity: Int = DEFAULT_OUT_CAPACITY,
    ): String? = synchronized(LOCK) { nativeTranscribe(pcm, language, keywords, wordTimestamps, outCapacity) }

    /** Transcricao continua: alimente ~1 s por chamada; comita o que duas passadas concordam. */
    fun streamProcess(
        pcm: FloatArray,
        language: String? = null,
        keywords: String? = null,
        outCapacity: Int = DEFAULT_OUT_CAPACITY,
    ): String? = synchronized(LOCK) { nativeStreamProcess(pcm, language, keywords, outCapacity) }

    /** Encerra o stream: a cauda vira texto comitado. */
    fun streamStop(outCapacity: Int = DEFAULT_OUT_CAPACITY): String? =
        synchronized(LOCK) { nativeStreamStop(outCapacity) }

    fun embed(text: String, outCapacity: Int = 4096): FloatArray? =
        synchronized(LOCK) { nativeEmbedText(text, outCapacity) }

    fun setAudio(language: String? = null, keywords: String? = null, wordTimestamps: Boolean = false) =
        synchronized(LOCK) { nativeSetAudio(language, keywords, wordTimestamps) }

    fun reset() = synchronized(LOCK) { nativeReset() }

    fun lastError(): String = synchronized(LOCK) { nativeLastError() }
}
