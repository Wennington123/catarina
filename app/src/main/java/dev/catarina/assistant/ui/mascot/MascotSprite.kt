package dev.catarina.assistant.ui.mascot

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject

/** Uma animacao do sheet: indices dos frames e quadros por segundo. */
data class MascotAnimation(val frames: List<Int>, val frameRate: Int)

/**
 * Sprite sheet da Catarina + animacoes, lidos de `mascot_config.json`. Os dois
 * arquivos vem da pasta `mascote_anim/` da raiz do repo (ver `sourceSets` no
 * `app/build.gradle.kts`), entao desenhar la ja entra no APK.
 */
data class MascotSprite(
    val image: ImageBitmap,
    val frameSize: Int,
    val animations: Map<String, MascotAnimation>,
) {
    /** Primeira animacao existente entre [keys] (permite fallback por fase). */
    fun animation(vararg keys: String): MascotAnimation? =
        keys.firstNotNullOfOrNull { animations[it]?.takeIf { a -> a.frames.isNotEmpty() } }

    companion object {
        private const val TAG = "MascotSprite"
        private const val CONFIG = "mascot_config.json"
        private const val SHEET = "sprite_sheet.png"

        fun load(context: Context): MascotSprite? = try {
            val json = JSONObject(context.assets.open(CONFIG).bufferedReader().use { it.readText() })
            val frameSize = json.optInt("grid_size", 64).coerceAtLeast(1)
            val bitmap = context.assets.open(SHEET).use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                Log.w(TAG, "$SHEET nao decodificou")
                null
            } else {
                MascotSprite(
                    image = bitmap.asImageBitmap(),
                    frameSize = frameSize,
                    animations = parseAnimations(json.optJSONObject("animations")),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sprite nao carregou: ${t.message}")
            null
        }

        private fun parseAnimations(obj: JSONObject?): Map<String, MascotAnimation> {
            if (obj == null) return emptyMap()
            val out = HashMap<String, MascotAnimation>()
            for (key in obj.keys()) {
                val anim = obj.optJSONObject(key) ?: continue
                val arr = anim.optJSONArray("frames") ?: continue
                val frames = (0 until arr.length()).map { arr.optInt(it) }
                if (frames.isEmpty()) continue
                out[key] = MascotAnimation(frames, anim.optInt("frame_rate", 2).coerceAtLeast(1))
            }
            return out
        }
    }
}
