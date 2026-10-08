package dev.catarina.assistant.assistant

import android.service.voice.VoiceInteractionService

/**
 * Registra a Catarina como assistente padrao do sistema (segurar o botao
 * assist). A sessao fica em `CatarinaSessionService`; o loop de voz roda no
 * `CatarinaService`.
 */
class CatarinaVoiceInteractionService : VoiceInteractionService()
