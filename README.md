# Catarina

Assistente de voz **nativo, offline e leve** para Android.
Escuta a wake word "Catarina", transcreve localmente e executa ações no device
(WiFi, Bluetooth, lanterna, brilho, volume, apps, ar-condicionado por IR,
notificações, SMS, shell) por voz.

Roda 100% on-device com os modelos **Needle 3** (tool calling / extração) e
**Whistle** (STT) da [Cactus Compute](https://github.com/cactus-compute/needle).
O único acesso à rede é o download dos pesos no primeiro uso — depois disso, nada.

## Stack

| Camada | Tecnologia | Offline |
|---|---|---|
| STT | Whistle (16.9 MB) | sim |
| Intenção → ação | Needle 3 (8–29 MB) | sim |
| Integração | Kotlin + JNI sobre a C API da Cactus | — |
| Sons | Sintetizados no app (`AudioTrack`, sem fala) | sim |
| UI + mascote | Sprite sheet (`mascote_anim/`) desenhado em Compose Canvas | — |
| Cérebro (opcional) | Provedor OpenAI-compatible (function calling) | não — só no modo API |

## Estrutura

```
mascote_anim/                # sprites da mascote (fonte única; o build empacota)
├── sprite_sheet.png         # 448×64 = 7 frames de 64px, fundo transparente
└── mascot_config.json       # grid, pivô e animações (frames + frame_rate)

app/src/main/
├── cpp/                     # shim JNI (needle_jni.cpp) + CMake
├── assets/tools.json        # schemas das ferramentas
├── assets/ir/ac.json        # códigos IR do ar-condicionado
└── java/dev/catarina/assistant/
    ├── core/                # CatarinaBrain (dono único do engine) + RemoteBrain
    ├── engine/              # wrapper do engine + parser + schema das tools
    ├── audio/               # captura 16 kHz mono + VAD + SoundKit/MascotSounds
    ├── model/               # download dos pesos com hash
    ├── tools/               # ToolExecutor + CallResolver (dedupe/gating)
    ├── wakeword/            # detector de wake word
    ├── service/             # CatarinaService (modelo quente + loop)
    ├── state/               # AssistantStateBus (fases p/ o mascote) + AppSettings
    ├── ui/                  # Compose: MainScreen, tema e bolha flutuante
    │   └── mascot/          # BabyMascot + MascotSprite (lê o sheet e o JSON)
    ├── tile/                # Quick Settings tile (liga/desliga o contínuo)
    └── assistant/           # VoiceInteraction + Recognition services
```

## Sprites da mascote

O desenho vem de `mascote_anim/` (raiz do repo): edite lá e o Gradle empacota
direto, sem passo de cópia (ver `sourceSets` no `app/build.gradle.kts`). O
`mascot_config.json` diz a grade, o pivô e cada animação (`frames` +
`frame_rate`); o `MascotSprite` lê isso e o `BabyMascot` desenha com
`FilterQuality.None`, mantendo o pixel art duro.

Fase → animação (com fallback, caso o nome não exista no JSON):

| Fase | Animação | Fallback |
|---|---|---|
| IDLE | `idle` | — |
| BOOTING | `booting` | `idle` |
| LISTENING | `listening` | `idle` |
| HEARING | `hearing` | `listening`, `idle` |
| THINKING | `thinking` | `listening`, `idle` |
| SPEAKING | `talking` | `idle` |
| CELEBRATING | `celebrating` | `listening`, `idle` |
| SAD | `sad` | `idle` |

O sheet tem 13 frames (`idle` 2, `talking` 3, `listening` 1, `sad` 1,
`thinking` 2, `celebrating` 2, `booting` 1, `hearing` 1). O anel de escuta, os
pontinhos de "pensando", o confetti e o anel de progresso são desenhados por
código por cima do sprite.

## Como funciona

```
wake word → AudioRecord 16 kHz mono → Whistle (STT) → Needle 3 (tool call)
          → CallResolver (dedupe + confiança) → ToolExecutor → som fofo
```

O engine é **process-global e não thread-safe**: o `NeedleEngine` serializa todas
as chamadas e o `CatarinaBrain` é o único dono da conversa.

## Cérebro local ou na nuvem

O padrão é **local** (Needle 3). Nas Configurações dá para ligar **Cérebro na
nuvem (API)**: um provedor **OpenAI-compatible** (OpenAI, Groq, Ollama na LAN,
etc.) com function calling. Nesse modo:

- o **STT continua local** (Whistle) — só o texto sai do aparelho;
- o **mesmo `tools.json`** vira a lista de `tools` do provedor, e o mesmo
  `CallResolver`/`ToolExecutor` executa: as ferramentas seguem no aparelho;
- o modelo devolve a resposta em linguagem natural (o `ANSWER`), então há
  conversa, memória de turno e português sem fine-tune.

Campos: URL base (incluindo `/v1`), modelo e API key (guardada em prefs privadas
do app; use **https** para provedores na internet — `http` só para rede local).
É opt-in explícito e quebra o "zero nuvem" do modo local.

### Planejado: cérebro via Termux (ponte ACP)

Para quem só usa o Command Code e não tem API key nenhuma. O CLI tem modo ACP
(`cmd acp` — *"Run as an ACP agent over stdio (for Zed, etc.)"*), mas **ACP é
stdio/JSON-RPC, não HTTP**: o cliente abre o processo e conversa pelo
stdin/stdout. Ou seja, não dá para colar no campo "URL base" — precisa de uma
ponte:

```
app  ──HTTP (OpenAI-compatible)──►  ponte  ──ACP / stdio──►  cmd acp
```

- A ponte rodaria no **Termux do próprio aparelho**: o app só fala HTTP e não
  consegue executar binário do Termux (UID diferente, precisaria de root).
- Com o agente ACP rodando no celular, ele enxerga o shell Android
  (`settings`, `cmd`, `svc`); o app continua sendo a frente de voz.
- Ressalvas: ACP é protocolo de **agente de código** (lê/escreve arquivo, roda
  comando), não uma API de chat — mapear as nossas tools vira trabalho da ponte,
  e o Termux precisa ficar vivo em background.

Objetivo: deixar os **dois modos lado a lado** nas Configurações (API e Termux),
para atender quem tem key e quem só tem o Command Code. Hoje só o modo API
existe.

## Ferramentas

| Ferramenta | Ação | Acesso |
|---|---|---|
| `set_wifi` / `set_bluetooth` | liga/desliga | root; senão API |
| `set_torch` | lanterna | API |
| `set_brightness` | brilho | root; senão `WRITE_SETTINGS` |
| `set_volume` | volume por stream | API |
| `open_app` | abre app por nome | API |
| `set_ac` | ar-condicionado | `ConsumerIrManager` + `assets/ir/ac.json` |
| `notify` | notificação | API |
| `send_sms` | envia SMS | `SEND_SMS` + confirmação |
| `run_shell` | comando (allowlist) | root |

Ações sensíveis (`send_sms`, `run_shell`, `set_ac`) exigem confirmação quando a
confiança fica abaixo do piso (0.7) — ver `CallResolver`.

O caminho root (`svc` para Wi-Fi/Bluetooth/brilho e o `run_shell`) é **opt-in**:
só roda com o toggle *Extras (root)* ligado nas Configurações do app — e com
`su` disponível no aparelho (o primeiro toque pede autorização no Magisk).

## Pré-requisitos

- **Android SDK** + **NDK r27+** (build com `16 KB page size`)
- **Python 3** + `cactus-needle` (só para baixar o engine nativo)
- JDK 17

## Buscar o engine nativo

O engine Android não é versionado (é artefato da Cactus):

```sh
./scripts/fetch-engine.sh
```

Isso baixa `android-arm64/` (`needle`, `libneedle.a`, `needle.h`) para
`app/src/main/cpp/`. O CMake falha com mensagem clara se ele não estiver lá.

## Build (GitHub Actions — sem SDK local)

Todo push em `main` dispara `.github/workflows/android-build.yml`, que monta o
APK de debug na nuvem (JDK 17 + NDK r27 + engine Cactus). O APK sai como
artifact `catarina-debug-apk` na página Actions da run — baixe e instale.
As versões empacotadas ficam na seção
[Releases](https://github.com/Wennington123/catarina/releases) (começando pela
`v0.1.0`).

## Build (no notebook)

```sh
./scripts/fetch-engine.sh
./gradlew :app:assembleDebug      # APK de debug
./gradlew :app:assembleRelease    # APK de release (precisa da sua keystore)
```

Só **`arm64-v8a`** é compilado (dispositivos 64-bit), o que mantém o APK enxuto.
No primeiro uso o app baixa `needle3.cact` (29 MB) e `whistle.cact` (16.9 MB) do
Hugging Face.

## Verificação rápida (opcional)

Como o dispositivo é aarch64/bionic, dá para validar o engine direto, sem montar
o APK — o teste reproduz exatamente as chamadas do shim JNI:

```sh
./scripts/fetch-engine.sh
clang++ -O2 -std=c++17 -Iapp/src/main/cpp/android-arm64 scripts/engine_test.cpp \
  app/src/main/cpp/android-arm64/libneedle.a -o /tmp/engine_test -lc++ -lm -llog
/tmp/engine_test app/src/main/cpp/android-arm64/needle3.cact \
  app/src/main/assets/tools.json "turn on the flashlight"
```

## Fine-tune (português)

O modelo base é English-centric — comandos em PT acertam a tool mas com confiança
baixa. Há uma receita pronta em `training/` (dataset em PT + `needle finetune` +
empacotamento do `.cact`), pra rodar no notebook. Ver `training/README.md`.

## Privacidade

- Inferência nunca toca a rede.
- Telemetria do engine desligada na build: `NEEDLE_TELEMETRY=0`, `DO_NOT_TRACK=1`.
- Zero Google / OpenAI no pipeline.
- Exceção única: o modo **Cérebro na nuvem (API)** é opt-in e manda só o texto
  transcrito ao provedor configurado — nesse caso não há "zero nuvem".

## Status

Implementado: engine/tools/service/assistente, download automático dos pesos,
mascote em pixel art (sprite sheet em `mascote_anim/`) com bolha flutuante
opt-in, sons fofos sintetizados (sem fala), Quick Settings tile, gate de root
opt-in, modo provedor externo (API OpenAI-compatible) opt-in e build remoto via
Actions. Falta assinatura release.
Achados do smoke test no A32 e o que ainda depende de fine-tune estão em
`docs/catarina-needle-apk.md` (§14).

## Roadmap

- [x] Estrutura, engine, JNI, tools, service, assistente
- [x] Download automático dos pesos + progresso (anel na mascote)
- [x] Mascote animada (Compose Canvas) + bolha flutuante opt-in
- [x] Mascote em pixel art (sprite sheet próprio em `mascote_anim/`)
- [x] Quick Settings tile
- [x] Gate de root opt-in (toggle nas Configurações)
- [x] Cérebro externo opt-in (API OpenAI-compatible com function calling)
- [x] Sons fofos sintetizados no app (sem fala / TTS)
- [x] Confirmação por voz que **retoma a ação** ("sim" executa, "não" descarta)
- [ ] Cérebro via **Termux (ponte ACP)** sobre o `cmd acp` — para quem só tem o
      Command Code e nenhuma API key (ver *Cérebro local ou na nuvem*)
- [ ] Configurações: escolher entre **API** e **Termux** (os dois modos lado a
      lado, cada um com suas opções)
- [ ] Fine-tune em português (comandos reais)
- [ ] KWS dedicado no lugar da wake word por transcrição
- [ ] Medir latência / RAM / bateria no dia a dia

Ver `docs/catarina-needle-apk.md` para a documentação completa.
