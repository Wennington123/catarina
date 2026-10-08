package dev.catarina.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.catarina.assistant.audio.AudioCapture
import dev.catarina.assistant.core.CatarinaBrain
import dev.catarina.assistant.service.CatarinaService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI minima: um turno por vez (para testar no device), baixar modelos com
 * progresso e ligar o modo continuo do service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var listen: Button
    private lateinit var serviceButton: Button
    private lateinit var permissionButton: Button

    private val brain: CatarinaBrain get() = (application as CatarinaApp).brain
    private val capture = AudioCapture()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        progress = findViewById(R.id.progress)
        listen = findViewById(R.id.listenButton)
        serviceButton = findViewById(R.id.serviceButton)
        permissionButton = findViewById(R.id.permissionButton)

        listen.setOnClickListener { onListen() }
        serviceButton.setOnClickListener { onStartService() }
        permissionButton.setOnClickListener { requestPermissions.launch(PERMISSIONS) }
    }

    /** O service roda como foreground do tipo microfone: precisa de RECORD_AUDIO antes. */
    private fun onStartService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions.launch(PERMISSIONS)
            return
        }
        CatarinaService.start(this)
        status.text = getString(R.string.notif_listening)
    }

    private fun onListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions.launch(PERMISSIONS)
            return
        }

        listen.isEnabled = false
        lifecycleScope.launch {
            try {
                status.text = getString(R.string.status_loading)
                brain.ensureLoaded { done, total, name -> showProgress(name, done, total) }
                hideProgress()

                status.text = getString(R.string.status_listening)
                val pcm = withContext(Dispatchers.IO) { capture.recordUtterance() }
                if (pcm.isEmpty()) {
                    status.text = getString(R.string.status_no_speech)
                    return@launch
                }

                val turn = brain.handleAudio(pcm)
                status.text = turn.status
            } catch (t: Throwable) {
                status.text = getString(R.string.status_error, t.message)
            } finally {
                hideProgress()
                listen.isEnabled = true
            }
        }
    }

    private fun showProgress(name: String, done: Long, total: Long) {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        runOnUiThread {
            progress.visibility = ProgressBar.VISIBLE
            progress.progress = pct
            status.text = getString(R.string.status_downloading, name, pct)
        }
    }

    private fun hideProgress() {
        runOnUiThread { progress.visibility = ProgressBar.GONE }
    }

    private companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.SEND_SMS,
        )
    }
}
