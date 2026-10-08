package dev.catarina.assistant.tile

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import dev.catarina.assistant.MainActivity
import dev.catarina.assistant.service.CatarinaService
import dev.catarina.assistant.state.AssistantStateBus
import dev.catarina.assistant.state.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Tile "Catarina" nas configurações rápidas: liga/desliga o modo contínuo.
 * Sem RECORD_AUDIO, o toque abre o app (tile não consegue pedir permissão).
 */
class CatarinaTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        sync()
        scope.launch {
            AssistantStateBus.phase.collect { sync() }
        }
    }

    override fun onStopListening() {
        scope.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            openApp()
            return
        }
        if (AssistantStateBus.base == Phase.LISTENING) {
            CatarinaService.stop(this)
        } else {
            CatarinaService.start(this)
        }
        sync()
    }

    private fun sync() {
        val tile = qsTile ?: return
        tile.state =
            if (AssistantStateBus.base == Phase.LISTENING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
