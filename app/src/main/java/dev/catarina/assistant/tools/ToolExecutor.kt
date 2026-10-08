package dev.catarina.assistant.tools

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import dev.catarina.assistant.engine.FunctionCall
import org.json.JSONObject

/** Resultado da execucao de uma ferramenta, realimentado ao engine. */
data class ToolResult(val ok: Boolean, val detail: String) {
    fun toJson(): String = JSONObject().put("ok", ok).put("detail", detail).toString()
}

/**
 * Executa os `function_calls`. Acoes sensiveis (SMS, shell, root) devem passar
 * por confirmacao antes de chegar aqui (ver gating por `confidence`).
 */
class ToolExecutor(private val context: Context) {

    private val cameras: CameraManager
        get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val audio: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun execute(call: FunctionCall): ToolResult = runCatching {
        when (call.name) {
            "set_torch" -> setTorch(call.arguments.optBoolean("on"))
            "set_volume" -> setVolume(call.arguments.optString("stream"), call.arguments.optInt("level"))
            else -> ToolResult(false, "${call.name} ainda nao implementado")
        }
    }.getOrElse { ToolResult(false, "erro: ${it.message}") }

    private fun setTorch(on: Boolean): ToolResult {
        val id = cameras.cameraIdList.firstOrNull { camId ->
            cameras.getCameraCharacteristics(camId)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult(false, "aparelho sem lanterna")
        cameras.setTorchMode(id, on)
        return ToolResult(true, "lanterna ${if (on) "ligada" else "desligada"}")
    }

    private fun setVolume(stream: String, level: Int): ToolResult {
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
}
