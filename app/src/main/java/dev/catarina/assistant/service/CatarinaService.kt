package dev.catarina.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.catarina.assistant.R

/**
 * Mantem o modelo quente e o microfone ativos em background. O engine e
 * process-global e nao thread-safe: este service deve ser o dono unico da
 * conversa (um turno por vez).
 */
class CatarinaService : Service() {

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Catarina", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_listening))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)

        // TODO: carregar os modelos, iniciar a wake word e o loop de audio.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "catarina"
        const val NOTIF_ID = 1
    }
}
