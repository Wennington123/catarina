package dev.catarina.assistant.state

import android.content.Context
import android.content.SharedPreferences
import dev.catarina.assistant.util.ShellRunner

/**
 * Preferencias do app. O root e **opt-in**: mesmo com `su` no aparelho, as
 * ferramentas privilegiadas (Wi-Fi/Bluetooth/brilho via `svc`, `run_shell`)
 * so rodam se o usuario ligar o toggle nas configuracoes.
 */
object AppSettings {

    private const val FILE = "catarina_settings"
    private const val KEY_ROOT = "root_enabled"
    private const val KEY_OVERLAY = "overlay_enabled"

    private lateinit var prefs: SharedPreferences

    /** Chamar em `CatarinaApp.onCreate()`, antes de qualquer uso. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var rootEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROOT, false)
        set(value) = prefs.edit().putBoolean(KEY_ROOT, value).apply()

    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY, value).apply()

    /**
     * Porta unica do root: toggle do usuario E `su` disponivel. O probe do
     * `ShellRunner.hasRoot` so dispara se o toggle estiver ligado.
     */
    fun rootAllowed(): Boolean = rootEnabled && ShellRunner.hasRoot

    // ---- Cerebro: local (Needle) ou provedor remoto (API) ----

    /**
     * Modo `remote` manda o texto (ja transcrito localmente) pra um endpoint
     * OpenAI-compatible com function calling. As ferramentas continuam no
     * aparelho; so a inteligencia vem da nuvem. Opt-in explicito: quebra o
     * "zero nuvem" do modo local.
     */
    var provider: String
        get() = prefs.getString(KEY_PROVIDER, PROVIDER_LOCAL) ?: PROVIDER_LOCAL
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    /** Base do endpoint, ja incluindo o prefixo de versao (ex.: `.../v1`). */
    var providerBaseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var providerModel: String
        get() = prefs.getString(KEY_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /**
     * Guardada em prefs privadas do app (`MODE_PRIVATE`), nao em codigo. Sem
     * `EncryptedSharedPreferences` pra nao puxar dependencia; em aparelho sem
     * root outro app nao le. Nao versione este valor.
     */
    var providerApiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    val providerRemote: Boolean get() = provider == PROVIDER_REMOTE

    val providerConfigured: Boolean
        get() = providerBaseUrl.isNotBlank() && providerModel.isNotBlank()

    const val PROVIDER_LOCAL = "local"
    const val PROVIDER_REMOTE = "remote"
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

    private const val KEY_PROVIDER = "provider"
    private const val KEY_BASE_URL = "provider_base_url"
    private const val KEY_MODEL = "provider_model"
    private const val KEY_API_KEY = "provider_api_key"
}
