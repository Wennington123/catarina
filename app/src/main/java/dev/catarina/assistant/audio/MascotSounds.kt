package dev.catarina.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Sons fofos da Catarina. Nao ha fala: sao bipes/arpejos curtos sintetizados. */
enum class MascotSound { LISTEN, HEAR, HAPPY, SAD, TALK }

/**
 * Sintetiza os sons em PCM 16-bit mono. Tudo gerado no app (nenhum asset), na
 * mesma linha "100% codigo" da mascote: senoide com envelope suave (ataque +
 * decaimento) e um toque de vibrato pra soar fofo em vez de "beep" de painel.
 */
object SoundKit {

    const val SAMPLE_RATE = 22_050

    private val cache = HashMap<MascotSound, ShortArray>()

    fun pcm(sound: MascotSound): ShortArray = cache.getOrPut(sound) { build(sound) }

    fun durationMs(sound: MascotSound): Long = pcm(sound).size * 1000L / SAMPLE_RATE

    private fun build(sound: MascotSound): ShortArray = when (sound) {
        MascotSound.LISTEN -> concat(tone(880f, 1320f, 70), gap(25), tone(1320f, 1760f, 95))
        MascotSound.HEAR -> tone(1180f, 1180f, 85)
        MascotSound.HAPPY -> concat(
            tone(660f, 660f, 70),
            gap(20),
            tone(880f, 880f, 70),
            gap(20),
            tone(1100f, 1100f, 70),
            gap(20),
            tone(1320f, 1320f, 120),
        )
        MascotSound.SAD -> concat(tone(700f, 600f, 140), gap(30), tone(560f, 430f, 200))
        MascotSound.TALK -> talk()
    }

    /** "Tagarela": alguns blips de alturas diferentes, no lugar da fala. */
    private fun talk(): ShortArray {
        val pitches = floatArrayOf(1046f, 880f, 1174f, 784f, 988f)
        val parts = ArrayList<ShortArray>(pitches.size * 2)
        pitches.forEachIndexed { i, f ->
            if (i > 0) parts.add(gap(35))
            parts.add(tone(f, f * 1.06f, 55))
        }
        return concat(*parts.toTypedArray())
    }

    private fun tone(f0: Float, f1: Float, ms: Int, amp: Float = 0.5f): ShortArray {
        val n = (SAMPLE_RATE * ms / 1000).coerceAtLeast(1)
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val vibrato = 1.0 + 0.010 * sin(2.0 * PI * 6.0 * t)
            val freq = (f0 + (f1 - f0) * t) * vibrato
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val env = if (t < 0.08) t / 0.08 else exp(-3.2 * (t - 0.08) / 0.92)
            out[i] = (sin(phase) * env * amp * Short.MAX_VALUE)
                .toInt()
                .coerceIn(-32768, 32767)
                .toShort()
        }
        return out
    }

    private fun gap(ms: Int) = ShortArray(SAMPLE_RATE * ms / 1000)

    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var offset = 0
        for (p in parts) {
            p.copyInto(out, offset)
            offset += p.size
        }
        return out
    }
}

/**
 * Toca os sons num `AudioTrack` em modo estatico (um som curto por vez). Usa
 * `USAGE_MEDIA` + `CONTENT_TYPE_SONIFICATION` pra tocar no volume de midia
 * (previsivel em qualquer aparelho), e nao no stream de assistente, que pode
 * estar mudo.
 *
 * O `AudioTrack` emite o som inteiro; a boca da mascote anima porque quem
 * chamou coloca a fase em `SPEAKING` pela duracao do som.
 *
 * Cada etapa e registrada no log: antes tudo era engolido por um `runCatching`
 * unico e uma falha virava silencio sem pista nenhuma. O track fica vivo ate o
 * proximo som (ou [stop]) — nao ha mais auto-release por marker, que era um
 * passo a mais entre o `write` e o `play`.
 */
object MascotSounds {

    private const val TAG = "MascotSounds"

    private var current: AudioTrack? = null

    /**
     * Toca o som e devolve o que aconteceu. O retorno existe pro teste de som na
     * tela: sem `adb`, o app mostra em qual passo parou e o volume de midia.
     */
    fun play(sound: MascotSound): String {
        val pcm = SoundKit.pcm(sound)
        stop()

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SoundKit.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrElse {
            Log.w(TAG, "nao consegui criar o AudioTrack ($sound)", it)
            return "falhou ao criar o AudioTrack: ${it.message}"
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack nao inicializou ($sound)")
            runCatching { track.release() }
            return "AudioTrack nao inicializou (estado ${track.state})"
        }

        val written = track.write(pcm, 0, pcm.size)
        if (written <= 0) {
            Log.w(TAG, "write devolveu $written ($sound)")
            runCatching { track.release() }
            return "write devolveu $written"
        }

        current = track
        runCatching { track.play() }.onFailure {
            Log.w(TAG, "play falhou ($sound)", it)
            current = null
            runCatching { track.release() }
            return "play falhou: ${it.message}"
        }
        return "tocou ${pcm.size} amostras (${SoundKit.durationMs(sound)} ms)"
    }

    /** Interrompe o som atual (ex.: outro comeca, ou o service morre). */
    fun stop() {
        val track = current ?: return
        current = null
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}
