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
| TTS | `TextToSpeech` nativo | sim |

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

## Privacidade

- Inferência nunca toca a rede.
- Telemetria do engine desligada na build: `NEEDLE_TELEMETRY=0`, `DO_NOT_TRACK=1`.
- Zero Google / OpenAI no pipeline.

## Status

Implementado (falta build/assinatura). Achados do smoke test no A32 e o que ainda
depende de fine-tune estão em `docs/catarina-needle-apk.md` (§14).

## Roadmap

- [x] Estrutura, engine, JNI, tools, service, assistente
- [x] Download automático dos pesos + progresso
- [x] Dedupe de chamadas + gating por confiança
- [ ] Fine-tune em português (comandos reais)
- [ ] KWS dedicado no lugar da wake word por transcrição
- [ ] Quick Settings tile
- [ ] Medir latência / RAM / bateria no dia a dia

Ver `docs/catarina-needle-apk.md` para a documentação completa.
