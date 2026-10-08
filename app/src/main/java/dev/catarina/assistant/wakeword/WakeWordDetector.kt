package dev.catarina.assistant.wakeword

/**
 * Detector de wake word. Dispara uma vez por deteccao; quem chamou decide
 * reiniciar depois do turno.
 */
interface WakeWordDetector {
    fun start(onDetected: (transcript: String) -> Unit)
    fun stop()
}
