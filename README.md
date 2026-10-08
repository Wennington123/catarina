# Catarina

Assistente de voz **nativo, offline e leve** para Android.
Escuta a wake word "Catarina", transcreve localmente e executa ações no device
(WiFi, lanterna, mídia, apps, IR, shell) por voz.

Roda 100% on-device com os modelos **Needle 3** (tool calling / extração) e
**Whistle** (STT) da [Cactus Compute](https://github.com/cactus-compute/needle).
O único acesso à rede é o download dos pesos no primeiro uso — depois disso, nada.

> **Status:** esqueleto inicial (`0.1.0`). Estrutura e contratos definidos;
> lógica de execução das ferramentas em implementação.

## Stack

| Camada | Tecnologia | Offline |
|---|---|---|
| STT | Whistle (16.9 MB) | sim |
| Intenção → ação | Needle 3 (8–29 MB) | sim |
| Integração | Kotlin + JNI sobre a C API da Cactus | — |
| TTS | `TextToSpeech` nativo | sim |

## Estrutura

```
app/src/main/
├── cpp/                 # shim JNI + CMake (engine android-arm64)
├── assets/tools.json    # schemas das ferramentas
└── java/dev/catarina/assistant/
    ├── audio/           # captura 16 kHz mono + VAD
    ├── engine/          # wrapper Kotlin sobre o JNI
    ├── model/           # download automático dos pesos
    ├── tools/           # registro e execução das ferramentas
    ├── service/         # foreground service (modelo quente)
    └── assistant/       # VoiceInteractionService (assistente padrão)
docs/                    # documentação base
scripts/                 # utilitários (fetch do engine)
```

## Pré-requisitos

- **Android SDK** + **NDK r27+** (build com `16 KB page size`)
- **Python 3** + `cactus-needle` (só para baixar o engine nativo)

## Buscar o engine nativo

O engine Android não é versionado (é artefato da Cactus):

```sh
./scripts/fetch-engine.sh
```

Isso baixa `android-arm64/` (`needle`, `libneedle.a`, `needle.h`) para
`app/src/main/cpp/`. O CMake falha com mensagem clara se ele não estiver lá.

## Build

```sh
./gradlew :app:assembleDebug
```

Só **`arm64-v8a`** é compilado (dispositivos 64-bit), o que mantém o APK enxuto.

## Privacidade

- Inferência nunca toca a rede.
- Telemetria do engine desligada na build: `NEEDLE_TELEMETRY=0`, `DO_NOT_TRACK=1`.
- Zero Google / OpenAI no pipeline.

## Roadmap

- [x] Estrutura do projeto e contratos (engine, tools, resposta)
- [x] Shim JNI + CMake
- [ ] Download automático dos pesos com verificação de hash
- [ ] Execução real das ferramentas (WiFi, lanterna, IR, mídia…)
- [ ] Wake word (Vosk ou sherpa-onnx)
- [ ] `VoiceInteractionService` + Quick Settings tile
- [ ] Medir latência / RAM / bateria no dispositivo
- [ ] Fine-tune com dados reais

Ver `docs/catarina-needle-apk.md` para a documentação completa.
