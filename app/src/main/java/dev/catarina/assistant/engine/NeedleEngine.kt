package dev.catarina.assistant.engine

import java.io.File

/**
 * Wrapper fino sobre o engine nativo do Needle/Whistle
 * (`libcatarina_jni.so`, que linka a `libneedle.a` estatica).
 *
 * O engine e process-global e NAO thread-safe: mantenha uma unica conversa por
 * processo e serialize as chamadas (ver `CatarinaService`).
 */
object NeedleEngine {

    init {
        System.loadLibrary("catarina_jni")
        // Antes de qualquer carga de modelo.
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

    private external fun nativeEmbedText(input: String, outCapacity: Int): FloatArray?

    // ---- API de alto nivel ----

    private const val DEFAULT_OUT_CAPACITY = 65536
    private const val DEFAULT_MAX_NEW_TOKENS = 512

    /** Carrega um `.cact` (texto ou fala) lido do disco. */
    fun load(weights: File) {
        val rc = nativeLoad(weights.readBytes())
        check(rc >= 0) { "needle_load falhou (rc=$rc): ${lastError()}" }
    }

    /** Registra system prompt e tools. Devolve o tamanho do prefixo tokenizado. */
    fun init(systemPrompt: String?, toolsJson: String?, toolIndexPath: String? = null): Int {
        val rc = nativeInit(systemPrompt, toolsJson, toolIndexPath)
        check(rc >= 0) { "needle_init falhou (rc=$rc): ${lastError()}" }
        return rc
    }

    fun models(): Int = nativeModels()

    fun completeText(
        input: String,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
        outCapacity: Int = DEFAULT_OUT_CAPACITY,
    ): NeedleResponse? = nativeCompleteText(input, maxNewTokens, outCapacity)?.let(NeedleResponse::parse)

    fun completeAudio(
        pcm: FloatArray,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
        outCapacity: Int = DEFAULT_OUT_CAPACITY,
    ): NeedleResponse? = nativeCompletePcm(pcm, maxNewTokens, outCapacity)?.let(NeedleResponse::parse)

    fun transcribe(
        pcm: FloatArray,
        language: String? = null,
        keywords: String? = null,
        wordTimestamps: Boolean = false,
        outCapacity: Int = DEFAULT_OUT_CAPACITY,
    ): String? = nativeTranscribe(pcm, language, keywords, wordTimestamps, outCapacity)

    fun embed(text: String, outCapacity: Int = 4096): FloatArray? = nativeEmbedText(text, outCapacity)

    fun setAudio(language: String? = null, keywords: String? = null, wordTimestamps: Boolean = false) =
        nativeSetAudio(language, keywords, wordTimestamps)

    fun reset() = nativeReset()

    fun lastError(): String = nativeLastError()
}
