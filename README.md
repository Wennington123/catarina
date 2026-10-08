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
| TTS | `TextToSpeech` nativo + pitch infantil | sim |
| UI + mascote | Jetpack Compose (Canvas pixel art, 100% código) | — |

## Estrutura

```
app/src/main/
├── cpp/                     # shim JNI (needle_jni.cpp) + CMake
├── assets/tools.json        # schemas das ferramentas
├── assets/ir/ac.json        # códigos IR do ar-condicionado
└── java/dev/catarina/assistant/
    ├── core/                # CatarinaBrain (dono único do engine)
    ├── engine/              # wrapper do engine + parser da resposta
    ├── audio/               # captura 16 kHz mono + VAD
    ├── model/               # download dos pesos com hash
    ├── tools/               # ToolExecutor + CallResolver (dedupe/gating)
    ├── wakeword/            # detector de wake word
    ├── service/             # CatarinaService (modelo quente + loop)
    ├── state/               # AssistantStateBus (fases p/ o mascote) + AppSettings
    ├── ui/                  # Compose: MainScreen, tema e bolha flutuante
    │   └── mascot/          # BabyMascot — a bebezinha em pixel art (Canvas, grid 64×64)
    ├── tile/                # Quick Settings tile (liga/desliga o contínuo)
    └── assistant/           # VoiceInteraction + Recognition services
```

## Como funciona

```
wake word → AudioRecord 16 kHz mono → Whistle (STT) → Needle 3 (tool call)
          → CallResolver (dedupe + confiança) → ToolExecutor → TTS
```

O engine é **process-global e não thread-safe**: o `NeedleEngine` serializa todas
as chamadas e o `CatarinaBrain` é o único dono da conversa.

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

## Status

Implementado: engine/tools/service/assistente, download automático dos pesos,
mascote animada (Compose Canvas) com bolha flutuante opt-in, Quick Settings
tile, gate de root opt-in e build remoto via Actions. Falta assinatura release.
Achados do smoke test no A32 e o que ainda depende de fine-tune estão em
`docs/catarina-needle-apk.md` (§14).

## Roadmap

- [x] Estrutura, engine, JNI, tools, service, assistente
- [x] Download automático dos pesos + progresso (anel na mascote)
- [x] Mascote animada (Compose Canvas) + bolha flutuante opt-in
- [x] Quick Settings tile
- [x] Gate de root opt-in (toggle nas Configurações)
- [ ] Fine-tune em português (comandos reais)
- [ ] KWS dedicado no lugar da wake word por transcrição
- [ ] Medir latência / RAM / bateria no dia a dia

Ver `docs/catarina-needle-apk.md` para a documentação completa.
