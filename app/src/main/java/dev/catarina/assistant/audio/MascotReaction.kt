package dev.catarina.assistant.audio

import dev.catarina.assistant.core.CatarinaBrain.Turn
import dev.catarina.assistant.state.AssistantStateBus
import dev.catarina.assistant.state.Phase

/**
 * Reacao da Catarina ao turno: som fofo + fase do mascote. Ela nao fala — a
 * boca se move enquanto o som toca (fase `SPEAKING`), o que da a impressao de
 * "tagarelar". Compartilhado pelo service e pela tela principal.
 */
fun reactToTurn(turn: Turn) {
    when (turn.kind) {
        Turn.Kind.ACTED -> {
            AssistantStateBus.flash(Phase.CELEBRATING)
            MascotSounds.play(MascotSound.HAPPY)
        }

        Turn.Kind.REFUSED, Turn.Kind.ERROR -> {
            AssistantStateBus.flash(Phase.SAD)
            MascotSounds.play(MascotSound.SAD)
        }

        Turn.Kind.ANSWER -> {
            AssistantStateBus.flash(Phase.SPEAKING, SoundKit.durationMs(MascotSound.TALK))
            MascotSounds.play(MascotSound.TALK)
        }
    }
}
