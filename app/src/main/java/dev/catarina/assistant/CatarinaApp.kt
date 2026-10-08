package dev.catarina.assistant

import android.app.Application
import dev.catarina.assistant.core.CatarinaBrain
import dev.catarina.assistant.state.AppSettings

class CatarinaApp : Application() {

    /** Dono unico do engine e da conversa (compartilhado por UI e service). */
    val brain by lazy { CatarinaBrain(this) }

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
    }
}
