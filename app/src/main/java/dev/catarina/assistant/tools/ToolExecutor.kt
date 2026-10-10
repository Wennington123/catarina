package dev.catarina.assistant.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.catarina.assistant.R
import dev.catarina.assistant.engine.FunctionCall
import dev.catarina.assistant.state.AppSettings
import dev.catarina.assistant.util.ShellRunner
import org.json.JSONObject

/** Resultado da execucao de uma ferramenta, realimentado ao engine. */
data class ToolResult(val ok: Boolean, val detail: String) {
    fun toJson(): String = JSONObject().put("ok", ok).put("detail", detail).toString()
}

/**
 * Executa os `function_calls`. O que chega aqui executa: o `CallResolver` ja
 * deduplicou e resolveu conflito, e nao ha confirmacao.
 */
class ToolExecutor(private val context: Context) {

    fun execute(call: FunctionCall): ToolResult =
        runCatching { dispatch(call) }.getOrElse { ToolResult(false, "erro: ${it.message}") }

    private fun dispatch(call: FunctionCall): ToolResult {
        val a = call.arguments
        return when (call.name) {
            "set_wifi" -> setWifi(a.optBoolean("on"))
            "set_bluetooth" -> setBluetooth(a.optBoolean("on"))
            "set_torch" -> setTorch(a.optBoolean("on"))
            "set_brightness" -> setBrightness(a.optInt("level", 50))
            "set_volume" -> setVolume(a.optString("stream", "media"), a.optInt("level", 50))
            "open_app" -> openApp(a.optString("name"))
            "set_ac" -> setAc(a)
            "notify" -> notify(a.optString("title", "Catarina"), a.optString("text", ""))
            "send_sms" -> sendSms(a.optString("to"), a.optString("text"))
            "run_shell" -> runShell(a.optString("cmd"))
            else -> ToolResult(false, "ferramenta desconhecida: ${call.name}")
        }
    }

    // ---- Rede ----

    private fun setWifi(on: Boolean): ToolResult {
        if (AppSettings.rootAllowed()) {
            val r = ShellRunner.run(listOf("svc", "wifi", if (on) "enable" else "disable"), asRoot = true)
            if (r.ok) return ToolResult(true, "Wi-Fi ${if (on) "ligado" else "desligado"}")
        }
        @Suppress("DEPRECATION")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        if (wm.setWifiEnabled(on)) return ToolResult(true, "Wi-Fi ${if (on) "ligado" else "desligado"}")
        return ToolResult(false, "Android 10+ bloqueia alternar Wi-Fi sem root")
    }

    private fun setBluetooth(on: Boolean): ToolResult {
        if (AppSettings.rootAllowed()) {
            val r = ShellRunner.run(listOf("svc", "bluetooth", if (on) "enable" else "disable"), asRoot = true)
            if (r.ok) return ToolResult(true, "Bluetooth ${if (on) "ligado" else "desligado"}")
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) return ToolResult(false, "aparelho sem Bluetooth")
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            @Suppress("DEPRECATION")
            if (if (on) adapter.enable() else adapter.disable()) {
                return ToolResult(true, "Bluetooth ${if (on) "ligado" else "desligado"}")
            }
        }
        return ToolResult(false, "sem permissao/root para alternar Bluetooth")
    }

    // ---- Luz e tela ----

    private fun setTorch(on: Boolean): ToolResult {
        val cameras = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cameras.cameraIdList.firstOrNull { camId ->
            cameras.getCameraCharacteristics(camId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult(false, "aparelho sem lanterna")
        cameras.setTorchMode(id, on)
        return ToolResult(true, "lanterna ${if (on) "ligada" else "desligada"}")
    }

    private fun setBrightness(level: Int): ToolResult {
        val v = level.coerceIn(0, 100) * 255 / 100
        if (AppSettings.rootAllowed()) {
            ShellRunner.run(listOf("settings", "put", "system", "screen_brightness_mode", "0"), asRoot = true)
            val r = ShellRunner.run(
                listOf("settings", "put", "system", "screen_brightness", v.toString()),
                asRoot = true,
            )
            if (r.ok) return ToolResult(true, "brilho em $level%")
        }
        if (Settings.System.canWrite(context)) {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            return ToolResult(true, "brilho em $level%")
        }
        return ToolResult(false, "precisa de WRITE_SETTINGS ou root")
    }

    private fun setVolume(stream: String, level: Int): ToolResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = audio.getStreamMaxVolume(s)
        audio.setStreamVolume(s, level.coerceIn(0, 100) * max / 100, 0)
        return ToolResult(true, "volume $stream = $level%")
    }

    // ---- Apps ----

    private fun openApp(name: String): ToolResult {
        if (name.isBlank()) return ToolResult(false, "nome do app vazio")
        val pm = context.packageManager
        val target = name.trim().lowercase()
        val app = pm.getInstalledApplications(PackageManager.GET_META_DATA).firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase().let { label -> label == target || label.contains(target) }
        } ?: return ToolResult(false, "app nao encontrado: $name")

        val intent = pm.getLaunchIntentForPackage(app.packageName)
            ?: return ToolResult(false, "app sem tela inicial: $name")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ToolResult(true, "abrindo ${pm.getApplicationLabel(app)}")
    }

    // ---- Ar-condicionado (emissor IR) ----

    private fun setAc(a: JSONObject): ToolResult {
        val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (ir == null || !ir.hasIrEmitter()) {
            return ToolResult(false, "aparelho sem emissor IR (use o caminho USB/Termux)")
        }
        val power = a.optBoolean("power", true)
        val codes = loadIrCodes()
        val key = if (!power) "off" else "${a.optString("mode", "cool")}:${a.opt("temp") ?: "default"}"
        val entry = codes.optJSONObject(key)
            ?: return ToolResult(false, "sem codigo IR para '$key' (preencha assets/ir/ac.json)")

        val pattern = entry.optJSONArray("pattern") ?: return ToolResult(false, "codigo IR invalido: $key")
        val freq = entry.optInt("freq", 38_000)
        val data = IntArray(pattern.length()) { pattern.optInt(it) }
        ir.transmit(freq, data)
        return ToolResult(true, "ar-condicionado: $key")
    }

    private fun loadIrCodes(): JSONObject = runCatching {
        context.assets.open("ir/ac.json").bufferedReader().use { JSONObject(it.readText()) }
    }.getOrElse { JSONObject() }

    // ---- Notificacoes ----

    private fun notify(title: String, text: String): ToolResult {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        mgr.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        return ToolResult(true, "notificacao: $title")
    }

    // ---- SMS ----

    private fun sendSms(to: String, text: String): ToolResult {
        if (to.isBlank() || text.isBlank()) return ToolResult(false, "destinatario/texto vazios")
        if (!hasPermission(Manifest.permission.SEND_SMS)) return ToolResult(false, "sem permissao SEND_SMS")
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        sms.sendTextMessage(to, null, text, null, null)
        return ToolResult(true, "SMS enviado para $to")
    }

    // ---- Shell (allowlist) ----

    private fun runShell(cmd: String): ToolResult {
        if (!AppSettings.rootAllowed()) {
            return ToolResult(false, "recursos root desativados (ative em Configurações)")
        }
        val parts = cmd.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ToolResult(false, "comando vazio")
        if (parts.first() !in SHELL_ALLOWLIST) {
            return ToolResult(false, "comando nao permitido: ${parts.first()}")
        }
        val r = ShellRunner.run(parts, asRoot = true)
        return ToolResult(r.ok, r.output.ifEmpty { if (r.ok) "ok" else "falhou" })
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL = "catarina-tools"
        val SHELL_ALLOWLIST = setOf("svc", "settings", "cmd", "am", "input", "getprop", "wm", "dumpsys")
    }
}
