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
| Voz → texto (PT) | **Vosk pt-BR** (modelo local, ~31 MB) | sim |
| Voz → texto (EN) | Whistle (16.9 MB) — **não tem português** | sim |
| Intenção → ação | Needle 3 (8–29 MB) | sim |
| Integração | Kotlin + JNI sobre a C API da Cactus | — |
| Sons | Sintetizados no app (`AudioTrack`, sem fala) | sim |
| UI + mascote | Sprite sheet (`mascote_anim/`) desenhado em Compose Canvas | — |
| Cérebro (opcional) | Provedor OpenAI-compatible (function calling) | não — só no modo API |

## Estrutura

```
mascote_anim/                # sprites da mascote (fonte única; o build empacota)
├── sprite_sheet.png         # 832×64 = 13 frames de 64px, fundo transparente
└── mascot_config.json       # grid, pivô e animações (frames + frame_rate)

scripts/
├── fetch-engine.sh          # baixa o engine nativo da Cactus
├── make-icon.py             # gera o ícone do app a partir da sprite
└── engine_test.cpp          # teste nativo do caminho do JNI

app/src/main/
├── cpp/                     # shim JNI (needle_jni.cpp) + CMake
├── assets/tools.json        # schemas das ferramentas
├── assets/ir/ac.json        # códigos IR do ar-condicionado
├── res/mipmap-<dpi>/        # ícone adaptativo (gerado por scripts/make-icon.py)
└── java/dev/catarina/assistant/
    ├── core/                # CatarinaBrain (dono único do engine) + RemoteBrain
    ├── engine/              # wrapper do engine + parser + schema/triggers das tools
    ├── audio/               # captura 16 kHz mono + VAD + SoundKit/MascotSounds
    ├── model/               # download dos pesos com hash
    ├── tools/               # ToolExecutor + CallResolver (dedupe)
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

### Ícone do app

O ícone também sai da sprite, não é desenhado à parte:

```sh
python3 scripts/make-icon.py
```

Ele lê o `idle_1` (frame 0 do `mascot_config.json`) e escreve:
`mipmap-<dpi>/ic_launcher_foreground.png` (ícone adaptativo, figura inteira),
`mipmap-<dpi>/ic_launcher_monochrome.png` (ícone temático do Android 13+) e
`drawable-nodpi/ic_launcher.png` (notificação e tile). Redesenhou a mascote?
Roda o script e o ícone acompanha.

Os glifos pequenos (notificação e temático) levam só a **cabeça**, com os olhos e
a boca vazados: num glifo de 24dp o corpo viraria um blob e, sem os furos, a
cabeça também — o script inunda a partir da borda e transforma em furo todo
detalhe escuro que está cercado de pele.

## Como funciona

```
"Ouvir agora" → AudioCapture (16 kHz mono + VAD) → Vosk pt-BR → texto
              → filtro de triggers: é um comando conhecido? não → recusa
              → Needle 3 (tool call) → CallResolver (dedupe) → ToolExecutor → som fofo
"Sempre ouvindo" → wake word (Whistle, inglês) → grava → mesmo caminho
```

O engine é **process-global e não thread-safe**: o `NeedleEngine` serializa todas
as chamadas e o `CatarinaBrain` é o único dono da conversa.

## Voz em português (e por que o STT é o Vosk)

O STT do Needle é o **Whistle**, e o Whistle **não tem português** — a lista
oficial é inglês, alemão, francês, espanhol, italiano, holandês e polonês. Com
ele, fala em PT vira texto sem sentido, nenhum `trigger` casa e nada é executado.
Era esse o "ela não ouve / não faz nada", com o microfone funcionando.

A primeira tentativa foi usar o reconhecedor do Android em `pt-BR`. Não serve
para este projeto: **o aparelho alvo não tem reconhecedor instalado** (é
degooglado), então o `SpeechRecognizer` caía no `CatarinaRecognitionService` — o
próprio app — que transcrevia com o Whistle. O resultado era um misto de inglês,
espanhol e pedaços de português.

A solução é o **Vosk com o modelo pequeno pt-BR** (`vosk-model-small-pt-0.3`,
~31 MB): baixado no primeiro uso, igual aos pesos do Needle, e rodando 100%
offline, sem Google. O turno de voz grava com o mesmo `AudioCapture` (16 kHz mono
+ VAD) e manda o PCM pro Vosk; o texto segue pro Needle, que decide a ferramenta.

- Nas Configurações dá para desligar o "modelo local" e usar o
  `SpeechRecognizer` do sistema — só vale a pena se o aparelho tiver um
  reconhecedor de verdade instalado.
- **Wake word:** "Sempre ouvindo" ainda depende do Whistle para ouvir
  "Catarina", ou seja, a hotword funciona em inglês. KWS em pt-BR está no
  roadmap.

## Comandos que ela entende

Em português o Needle acerta a ferramenta pelo `trigger` (regex por ferramenta em
`assets/tools.json`), não pela confiança. O app usa esses mesmos `triggers` como
filtro: **se o pedido não casa com nenhum, ela recusa** em vez de chutar uma
ferramenta — era o "tudo vira brilho 100%". Estes exemplos casam:

| Peça | Exemplos |
|---|---|
| Lanterna | "liga a lanterna", "acende a luz", "desliga a lanterna" |
| Wi-Fi | "liga o wi-fi", "desliga o wifi" |
| Bluetooth | "liga o bluetooth" |
| Brilho | "brilho em 40" |
| Volume | "volume no 30", "aumenta o som" |
| Abrir app | "abre o WhatsApp" |
| Notificação | "me lembra de tomar água" |
| Mensagem | "manda mensagem pro João" |
| Ar-condicionado | "liga o ar em 22" (precisa de emissor IR) |
| Comando | "roda o comando svc wifi enable" (com root) |

Fora desses padrões ela responde que não conhece **e mostra o que ouviu**
("Ouvi: ...") — é assim que se distingue "o reconhecimento errou" de "essa fala
está fora do vocabulário". Conversa aberta é no modo API. A mesma lista aparece
no app, na seção *Comandos que ela entende*.

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
| `send_sms` | envia SMS | `SEND_SMS` |
| `run_shell` | comando (allowlist) | root |

Não há confirmação: se o engine pediu a ferramenta, ela executa — mas **só depois
que o pedido casou com o `trigger`** da ferramenta (ver *Comandos que ela
entende*). O `CallResolver` remove chamadas duplicadas e, em conflito (mesma
ferramenta com argumentos diferentes), fica com a primeira. Em português o Needle
devolve confiança 0.02–0.30 — pedir confirmação nesse cenário significava **nunca
executar nada**.

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
- Tudo local: Needle, Whistle e agora o **Vosk pt-BR** rodam no aparelho — sem
  Google, sem conta, e a inferência nunca toca a rede.
- Exceção única, opt-in nas Configurações: o modo **Cérebro na nuvem (API)**, que
  manda só o texto transcrito ao provedor configurado.

## Status

Implementado: engine/tools/service/assistente, download automático dos pesos,
voz em pt-BR pelo Vosk local (o Whistle do Needle não tem português),
mascote em pixel art (sprite sheet em `mascote_anim/`) com bolha flutuante
opt-in, sons fofos sintetizados (sem fala), banner de capacidades e seção de
comandos na tela, execução direta (sem confirmação) com filtro por `trigger`,
Quick Settings tile, gate de root opt-in, modo provedor externo (API
OpenAI-compatible) opt-in e build remoto via Actions. Falta assinatura release.
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
- [x] Banner de capacidades na abertura (dispensável e reabrível)
- [x] Executa direto no pedido — **sem confirmação** (o gate em PT travava tudo)
- [x] Seção de comandos na tela + filtro por `trigger` (o que não é comando é recusado)
- [x] Voz em pt-BR pelo **Vosk** offline (o Whistle do Needle não tem português, e o aparelho não traz reconhecedor do sistema)
- [ ] Cérebro via **Termux (ponte ACP)** sobre o `cmd acp` — para quem só tem o
      Command Code e nenhuma API key (ver *Cérebro local ou na nuvem*)
- [ ] Configurações: escolher entre **API** e **Termux** (os dois modos lado a
      lado, cada um com suas opções)
- [ ] Fine-tune em português (comandos reais)
- [ ] KWS dedicado em pt-BR no lugar da wake word por transcrição (hoje a hotword depende do Whistle, só em inglês)
- [ ] Medir latência / RAM / bateria no dia a dia

Ver `docs/catarina-needle-apk.md` para a documentação completa.
