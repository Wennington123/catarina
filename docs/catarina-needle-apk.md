# Catarina — Assistente de Voz Nativo (APK) com Needle 3 + Whistle

Documentação base para portar a assistente **Catarina** de Termux/Whisper para um
**APK Android nativo leve**, usando os modelos **Needle 3** (tool calling) e
**Whistle** (STT) da Cactus Compute, rodando 100% on-device.

> Doc companheira de `jarvis-doc.md` (versão Termux). Esta descreve o caminho APK.

## 0. Nome e identidade

- **Assistente / app:** **Catarina** (label do launcher e nome do APK).
- **Wake word:** "Catarina".
- **Pacote sugerido:** `dev.catarina.assistant` (ajustável).
- "Jarvis" fica apenas como nome do *projeto guarda-chuva* herdado do Termux; o
  produto que o usuário vê chama-se **Catarina**.

---

## 1. Objetivo

Um app Android **enxuto**, integrado ao sistema, que escuta a wake word,
transcreve localmente e **chama ferramentas do device** (WiFi, IR, mídia, apps,
notificações, shell) por voz — sem nuvem de STT e sem telemetria.

Diferença central em relação ao `jarvis-doc.md`: o cérebro de *intenção →
ação* deixa de ser um LLM remoto (`cmd -p`) e passa a ser o **Needle 3** local,
que devolve `function_calls` prontos. O Claude/Command Code continua disponível
como *fallback* de conversa, mas não é necessário para comandos.

## 2. Por que trocar (comparativo)

| Camada | Stack Termux atual | Stack APK Needle |
|---|---|---|
| STT | Whisper.cpp tiny/base (~400–500 MB RAM) | **Whistle 16.9 MB** (~tensão de RAM muito menor) |
| Intenção→ação | `cmd -p` (rede + ~100 MB) | **Needle 3** (8–29 MB, offline) |
| Tool calling | LLM improvisa (shell livre) | Gramática garante o call (JSON válido) |
| Latência | 5–12 s/turno | alvo: < 1–2 s (sem rede) |
| Rede | API Anthropic | **nenhuma** após o 1º download |
| Empacotamento | scripts no Termux | **APK nativo** (Kotlin + JNI) |

RAM/economia importam: o A32 tem limites, e outra sessão de trabalho já mirou
economia de bateria/RAM — o Needle é bem mais leve que Whisper.

## 3. Arquitetura

```
┌───────────────────────────────────────────────┐
│  APK (Kotlin)                                  │
│                                                │
│  [Wake word KWS]  → [AudioRecord 16 kHz mono]  │
│         │                     │                │
│         ▼                     ▼                │
│  ┌──────────────┐     ┌──────────────────┐     │
│  │ Whistle STT  │────▶│  Needle 3 (tools)│     │
│  │ (JNI)        │ txt │  (JNI)           │     │
│  └──────────────┘     └────────┬─────────┘     │
│                                │ function_calls│
│                                ▼               │
│                     ┌──────────────────────┐   │
│                     │ Tool Executor        │   │
│                     │ (Android API / root /│   │
│                     │  Termux / IR)        │   │
│                     └──────────┬───────────┘   │
│                                ▼               │
│                     Sons fofos (AudioTrack)     │
└───────────────────────────────────────────────┘
```

- **Um engine cobre os dois modelos.** A pasta `android-arm64` traz engine
  (< 1 MB) + `libneedle.a` + `needle.h`; `needle_load` carrega tanto
  `needle3.cact` quanto `whistle.cact`. `needle_complete` aceita áudio direto:
  entra clipe, sai tool call com os campos de fala prefixados por `audio_`.
- **Engine é process-global e não thread-safe** → manter o modelo carregado num
  único processo (foreground service), uma conversa por vez. Não recriar por turno.

## 4. Camadas e arquivos do projeto

```
jarvis/
├── app/                       # módulo Android (Kotlin)
│   ├── src/main/
│   │   ├── java/.../
│   │   │   ├── audio/         # AudioRecord 16 kHz mono, VAD, streaming
│   │   │   ├── engine/        # Wrapper Kotlin sobre o JNI (Needle + Whistle)
│   │   │   ├── tools/         # Registro de ferramentas + executores
│   │   │   ├── wakeword/      # KWS (ver §8)
│   │   │   ├── service/       # Foreground service (modelo quente + mic)
│   │   │   ├── state/         # AssistantStateBus (fases) + AppSettings (root/bolha)
│   │   │   ├── ui/            # Compose: MainScreen + tema + bolha flutuante
│   │   │   │   └── mascot/    # BabyMascot (Canvas, reage às fases)
│   │   │   ├── tile/          # Quick Settings tile (modo contínuo)
│   │   │   └── assistant/     # VoiceInteractionService (assistente padrão)
│   │   └── cpp/               # Shim JNI + CMakeLists
│   │       ├── needle_jni.cpp
│   │       └── CMakeLists.txt
│   └── src/main/assets/
│       ├── tools.json         # Schemas das ferramentas (ver §6)
│       └── models/            # (vazio se download on-demand)
├── docs/
│   └── jarvis-needle-apk.md   # este arquivo
└── README.md
```

### 4.1 Shim JNI (`needle_jni.cpp`)

A C API confirmada em `needle.h`:

- `needle_load` — carrega um `.cact` (texto ou fala)
- `needle_init` — inicializa com system prompt, tools JSON, tool index
- `needle_complete` — um turno (texto **ou** áudio)
- `needle_transcribe` — STT puro
- `needle_embed` — embedding (retrieval / memória)
- `needle_models` — quais modelos estão carregados
- `needle_set_audio` — idioma, bias de keywords, word timestamps

Expomos pro Kotlin via JNI algo como:

```kotlin
object NeedleEngine {
  external fun load(weightsPath: String): Int
  external fun init(toolsJson: String, system: String?): Int
  external fun complete(input: String, maxNewTokens: Int): String  // JSON
  external fun transcribe(pcm16: ShortArray, sampleRate: Int): String
  external fun embed(text: String): FloatArray
  external fun reset()
}
```

`needle_jni.cpp` só marshala strings/arrays e chama a C API; toda a lógica fica
no Kotlin. Linka `libneedle.a` estática no `CMakeLists.txt`.

## 5. Pesos e download automático

- **APK mínimo (~5 MB)**, sem pesos embutidos.
- No **1º launch**, baixar por HTTPS direto do Hugging Face:
  - `needle3.cact` — de `Cactus-Compute/needle3` (29 MB em 20 layers; 8 MB em 4)
  - `whistle.cact` — de `Cactus-Compute/whistle` (16.9 MB)
- O usuário **nunca** precisa ir no repo: onboarding com barra de progresso e
  checagem de integridade (SHA-256). Salvar em pasta privada do app.
- **Depth:** começar com **4 layers (8 MB)** para latência/RAM no A32; permitir
  trocar pro full (20L) nas configurações. Fine-tune no produto depois (§10).
- **Boot sem Whistle:** baixar `needle3.cact` primeiro (necessário pra iniciar),
  `whistle.cact` em paralelo/opcional.
- **Fallback offline:** retry com backoff; opção de apontar pra arquivo local
  (ex.: `adb push` ou cópia manual) se não houver rede.

## 6. Ferramentas (contrato)

`tools.json` é um array no formato que o engine consome (OpenAI-style ou forma
compacta). Regras de design que a própria Cactus recomenda: **uma ferramenta por
ação**, nomes que o usuário falaria, formato na descrição, restrições na
gramática (enum/min/max), ≤ 5 ferramentas (ou usar retrieval).

Cobrindo o `jarvis-doc.md`, o catálogo inicial (mapeado p/ o executor):

| Ferramenta | Argumentos | Executor |
|---|---|---|
| `set_wifi` | `on: bool` | `WifiManager` / `svc wifi` (root) |
| `set_bluetooth` | `on: bool` | `BluetoothAdapter` / `svc bluetooth` |
| `set_torch` | `on: bool` | `CameraManager.setTorchMode` |
| `set_brightness` | `level: int(0-100)` | `Settings.System` / root |
| `set_volume` | `stream: enum, level: int` | `AudioManager` |
| `open_app` | `name: str` | `PackageManager`/`am start` |
| `send_sms` | `to: str, text: str` | `SmsManager` (com confirmação) |
| `notify` | `title: str, text: str` | `NotificationManager` |
| `set_ac` | `power: bool, temp: int(16-30), mode: enum` | **IR blaster** (scripts `send_*` / tiqiaa) |
| `run_shell` | `cmd: str` | Termux / root (com allowlist) |

Regras de segurança: ações destrutivas/sensíveis (SMS, shell, root) exigem
**confirmação** quando `confidence` abaixo do limiar (§7). `run_shell` com
allowlist de binários.

## 7. Contrato de resposta e confidence

Cada turno devolve um JSON:

```json
{
  "type": "call",
  "function_calls": [{"name": "set_ac", "arguments": {"power": true, "temp": 22, "mode": "cool"}}],
  "reasoning": "'liga o ar' -> power true; '22' -> temp",
  "confidence": 0.94,
  "suppressed_calls": [],
  "prefill_tps": 4300.0,
  "decode_tps": 850.0,
  "peak_ram_mb": 41.2
}
```

Roteamento (gating):

```kotlin
val r = engine.complete(text)
when {
  r.calls.isNotEmpty() && r.confidence >= 0.7 -> execute(r.calls)        // age
  r.calls.isNotEmpty() || r.suppressed.isNotEmpty() -> confirm(r)        // pergunta
  else -> say("Não consigo fazer isso aqui")                             // recusa
}
```

- `function_calls` vazio = recusa (off-topic) — **sempre** tratar.
- Argumentos só vêm com evidência no texto; campos opcionais podem faltar.
- `validation.ungrounded` lista campos não ancorados — recusar/confirmar.
- Environment facts opcionais: `date`, `locale`, `device`, `battery`, `network`,
  `location` (passar como **fatos**, nunca instruções).

## 8. Integração com o sistema

Sem root, o app pode:

- **`VoiceInteractionService`** → virar o **assistente padrão** (segurar botão
  assist / atalho), com acesso a `VoiceInteractionSession`.
- **Quick Settings Tile** e **share-target**.
- **Foreground service** de mic + modelo quente (com notificação persistente).

Com **root (Magisk)**, amplia: `svc wifi/bluetooth`, `settings put`, acesso a
`/sys`, LSPosed pra hooks, e o IR blaster (`baylibre`/`tiqiaa`) pro ar-condicionado.

**Wake word (decisão em aberto):** o Needle não faz KWS. Opções a avaliar:
- **Vosk KWS** (já usado no `jarvis-doc.md`, ~50 MB) — reaproveita o que tu tem.
- **sherpa-onnx KWS** — modelo de wake word pequeno, offline.
- Alternativa: acionar por **botão/tile** e pular hotword (economiza RAM/bateria).

## 9. Áudio

- `AudioRecord` em **16 kHz mono** (exigência do Whistle).
- VAD simples (energia/RMS) pra cortar silêncio antes de transcrever.
- Microfone é recurso único: o `AudioRecord` do wake word e o do turno disputam
  o device. A captura é cancelável (`AudioCapture.cancel()`, que para o
  `AudioRecord`) porque `Thread.interrupt()` não destrava um `read` bloqueado —
  sem isso o wake word segura o mic e o turno grava silêncio.
- Whistle: até 30 s por passada; `needle.stream(chunks)` pra transcrição contínua
  (compromete as palavras que duas passadas concordam).
- Som: a Catarina **não fala**. Ela emite sons curtos sintetizados no app
  (`SoundKit`: senoide com envelope e vibrato; `AudioTrack` estático), um por
  evento — armar escuta, te ouvir, sucesso, recusa, confirmação e "tagarelar".
  A boca da mascote anima porque a fase vira `SPEAKING` pela duração do som.
  Toca em `USAGE_MEDIA`/`SONIFICATION` (volume de mídia, previsível).

## 10. Privacidade e telemetria

- **Desligar telemetria na build**: compilar com `NEEDLE_TELEMETRY=0` e
  `DO_NOT_TRACK=1` (ou setar em runtime/`BuildConfig`).
- Inferência **nunca** toca a rede. A única rede é o **download dos pesos** no
  1º uso — documentar isso no onboarding.
- Zero Google/OpenAI no pipeline.

## 11. Build

- **NDK + CMake**, ABI única **`arm64-v8a`** (o A32 é 64-bit) → menor tamanho.
- Setar **16 KB page size** (requisito Android 15+).
- Prototipagem rápida antes do Gradle: baixar a pasta `android-arm64` e rodar o
  runner direto no Termux (é bionic-nativo, roda sem proot):
  ```
  ./needle --model needle3.cact --tools tools.json --prompt "liga o ar em 22"
  ```
- Build do APK: SDK + JDK + Gradle (viável no Termux com esforço, ou num PC).

## 12. Riscos e limitações

- Binding JNI/Java **não é fornecido** pelo repo — escrevemos o shim (escopo pequeno, mas real).
- Engine global/não thread-safe → arquitetura de 1 processo, 1 conversa.
- Precisão do Needle em comandos fora do conjunto de tools → recusa (esperado).
  Melhora com **fine-tune** nos teus tools reais.
- Não gera texto livre: conversa aberta continua via Command Code (fallback).
- Acúmulo de RAM do engine: medir `peak_ram_mb` no A32 e ajustar depth.

## 13. Próximos passos

1. Validar o runner `android-arm64` no Termux com `tools.json` mínimo.
2. Escrever `tools.json` inicial (tabela §6) e testar acertos/recusas.
3. Criar o projeto Android + shim JNI (`needle_jni.cpp`) + CMake.
4. Onboarding + download automático dos pesos (com hash).
5. Foreground service (modelo quente) + áudio 16 kHz + VAD.
6. Executores das ferramentas (Wifi/Bluetooth/Torch/IR…).
7. `VoiceInteractionService` (assistente padrão) + Quick Settings tile.
8. Decidir wake word (§8) e medir latência/RAM/bateria no A32.
9. (Opcional) Fine-tune com dados reais → `.cact` tunado.

## 14. Validação inicial (smoke test no Galaxy A32)

O runner `android-arm64` foi executado direto no Termux com `needle3.cact`
(29 MB) e o `tools.json` do repo. Os números abaixo vêm do próprio engine
(`peak_ram_mb`, `prefill_tps`, `decode_tps`).

**O engine roda bem no aparelho:** ~76 MB de RAM, prefill 55–220 tps, decode
8–115 tps. Viável.

**Achados que mudaram o design das tools:**

1. **Descrições em inglês.** Com descrições em português o modelo não ligava
   "flashlight" a "lanterna" e escolhia a ferramenta errada. As descrições agora
   são em inglês (os nomes já eram).
2. **`triggers` resolvem o roteamento.** Com >5 tools declaradas, o retrieval
   pegava a ferramenta errada ("flashlight" → `set_bluetooth`). Adicionar
   `triggers` (regex PT+EN) por ferramenta corrigiu o roteamento — inclusive em
   português — sem fine-tune.
3. **Português ainda precisa de fine-tune.** Pelo trigger a chamada sai certa,
   mas com `confidence` perto de 0 (0.02–0.30) e às vezes duplicada/contraditória.
   Para confiar em PT a sério, treinar com dados reais (`needle finetune`).
4. **Cuidado com `triggers`:** um request que casa com trigger **emite a chamada
   mesmo abaixo do piso de confiança**. O app precisa deduplicar chamadas e
   exigir confirmação em ações sensíveis.

**Amostra de resultados:**

| Entrada | Saída | Conf. |
|---|---|---|
| `turn on the flashlight` | `set_torch(on=true)` | 0.84 |
| `set brightness to 40` | `set_brightness(level=40)` | 0.94 |
| `desliga o wifi` | `set_wifi(on=false)` | 0.26 |
| `liga a lanterna` | `set_torch(on=true)` | 0.02 |

**Implicação:** o pipeline funciona ponta a ponta no device; o que falta para PT
confiável é fine-tune com comandos reais da Catarina.

### 14.1 Validação nativa do caminho do JNI

Além do runner, foi compilado no próprio device um teste C (`scripts/engine_test.cpp`)
que reproduz **exatamente** as chamadas do shim JNI (carregar os pesos da memória,
`needle_init`, `needle_complete` com buffer de saída). Resultado no A32:

```
needle_load -> 0 (models=0x1)                 # modelo de texto
needle_load(whistle 16919407 bytes) -> 0 (models=0x3)  # texto + fala
needle_init -> 474                            # prefixo tokenizado
needle_complete -> 40
{"function_calls":[{"name":"set_torch","arguments":{"on":true}}],"confidence":0.8434}
```

Ou seja: o caminho que o APK usa (dois modelos no mesmo engine, saída em buffer)
foi verificado no hardware alvo.

## 15. Cérebro externo (opt-in)

O modo local (Needle 3) é o padrão. Nas Configurações dá para ligar **Cérebro na
nuvem (API)**: qualquer endpoint **OpenAI-compatible** com function calling.

- STT continua local (Whistle) — só o texto vai pro provedor.
- `ToolSchema.toOpenAi(tools.json)` converte o mesmo `tools.json` pra lista de
  `tools` do provedor; o mesmo `CallResolver`/`ToolExecutor` executa. As
  ferramentas seguem no aparelho.
- `RemoteBrain` faz o loop de function calling: executa a chamada, devolve o
  resultado como `role: tool` e aí recebe a resposta em linguagem natural
  (`Turn.Kind.ANSWER`), que aparece na tela e vira o som de "tagarelar".
- Histórico multi-turn em memória (24 mensagens, com system prompt fixo).
- Ações sensíveis seguem o mesmo gating (`send_sms`, `run_shell`, `set_ac`).
- API key em prefs privadas do app (`MODE_PRIVATE`); `http` liberado só para
  rede local (provedor caseiro), **https** para internet.

Quebra o "zero nuvem" — por isso é opt-in explícito.

---
*Catarina — assistente de voz nativo com Needle 3 + Whistle. Offline-first.*
