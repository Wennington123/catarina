package dev.catarina.assistant.assistant

import android.service.voice.VoiceInteractionService

/**
 * Registra a Catarina como assistente padrao do sistema (segurar o botao
 * assist). A logica de sessao fica em `CatarinaSessionService`.
 */
class CatarinaVoiceInteractionService : VoiceInteractionService()
