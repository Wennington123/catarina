package dev.catarina.assistant.ui.overlay

import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.catarina.assistant.service.CatarinaService
import dev.catarina.assistant.state.AssistantStateBus
import dev.catarina.assistant.ui.mascot.BabyMascot

/**
 * Bolha flutuante: a bebezinha sobre outros apps, reagindo ao estado do
 * assistente. Toque pede um turno rápido. Requer SYSTEM_ALERT_WINDOW
 * (o toggle da tela principal conduz a permissão).
 *
 * O ComposeView fora de Activity precisa dos três "view tree owners"
 * (lifecycle, saved state, view model store) — este service fornece todos.
 */
class MascotOverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore
        get() = store

    private var overlayView: View? = null

    override fun onCreate() {
        savedStateController.performRestore(null)
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        addOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        overlayView?.let {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
        }
        overlayView = null
        store.clear()
        super.onDestroy()
    }

    private fun addOverlayView() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (16 * density).toInt()
            y = (140 * density).toInt()
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MascotOverlayService)
            setViewTreeSavedStateRegistryOwner(this@MascotOverlayService)
            setViewTreeViewModelStoreOwner(this@MascotOverlayService)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent {
                val phase by AssistantStateBus.phase.collectAsState()
                val progress by AssistantStateBus.progress.collectAsState()
                BabyMascot(phase, progress, Modifier.size(120.dp))
            }
            setOnClickListener {
                // Se o app está em background o Android pode bloquear o FGS;
                // nesse caso a bolha ignora o toque sem quebrar.
                runCatching { CatarinaService.listen(this@MascotOverlayService) }
            }
        }

        wm.addView(view, params)
        overlayView = view
    }

    companion object {
        fun start(context: android.content.Context) =
            context.startService(Intent(context, MascotOverlayService::class.java))

        fun stop(context: android.content.Context) =
            context.stopService(Intent(context, MascotOverlayService::class.java))
    }
}
