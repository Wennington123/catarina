package dev.catarina.assistant

import android.app.Application
import dev.catarina.assistant.model.ModelRepository

class CatarinaApp : Application() {
    val models by lazy { ModelRepository(this) }
}
