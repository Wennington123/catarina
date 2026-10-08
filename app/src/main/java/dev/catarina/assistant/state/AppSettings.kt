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
}
