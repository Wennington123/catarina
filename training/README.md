# Fine-tune da Catarina em português

O modelo base do Needle é **English-centric**: o smoke test no A32 (§14 da doc)
mostrou que comandos em PT acertam a tool via `triggers`, mas com confiança perto
de zero. Fine-tune com comandos reais resolve isso.

> Isto roda no **notebook** (JAX treina em CPU/CUDA/Metal) — não no Termux.

## 1. Dataset

- `training/pairs_pt.json` — pares `query → answers` em PT (fonte, editável à mão).
- `training/build_dataset.py` — junta com o `tools.json` do app e gera o JSONL.
- `training/data/catarina_pt.jsonl` — saída (56 exemplos: 46 com chamada, 10 recusas).

Formato de cada linha (o que o `needle finetune` consome):

```json
{"query": "liga a lanterna", "tools": [ ...schemas... ], "answers": [{"name": "set_torch", "arguments": {"on": true}}]}
```

Regerar depois de editar os pares:

```sh
python3 training/build_dataset.py
```

Recusas são exemplos com `"answers": []` — ensinam o modelo a não inventar.

## 2. Treinar (local, LoRA, 4-bit)

```sh
pip install "cactus-needle[train]"

needle finetune training/data/catarina_pt.jsonl \
  --epochs 10 --val-split 0.1 \
  --lora-rank 16 --lora-alpha 32 --lr 1e-4 --batch-size 16 \
  --out training/catarina_lora.safetensors
```

O `finetune` roda no rung completo (20 layers) e treina só adapters LoRA
(base congelada, mesclada no export). O dataset é **só seu** — nada sai da máquina.

Quer mais exemplos sem escrever à mão? `needle generate-data --tools app/src/main/assets/tools.json --num-samples 500 --output training/data/gerado.jsonl`
usa um LLM via OpenRouter (`OPENROUTER_API_KEY`) — opcional, e quebra o
offline/privacidade, então só se você topar.

## 3. Empacotar o `.cact`

```sh
needle build checkpoints/needle3.safetensors \
  --lora training/catarina_lora.safetensors \
  --layers 8 \
  --out training/catarina.cact
```

`--layers` escolhe o rung (2 a 20). **8 layers** é um bom equilíbrio no A32.

## 4. Validar antes de instalar

```sh
./app/src/main/cpp/android-arm64/needle \
  --model training/catarina.cact \
  --tools app/src/main/assets/tools.json \
  --prompt "liga a lanterna"
```

Confira o `function_calls` e o `reasoning`.

## 5. Colocar no app

O app usa `filesDir/models/needle3.cact` — se o arquivo já existir, ele **não baixa
de novo**. Então basta empurrar o modelo treinado com esse nome:

```sh
adb push training/catarina.cact /sdcard/catarina.cact
adb shell run-as dev.catarina.assistant cp /sdcard/catarina.cact files/models/needle3.cact
```

(Alternativa: subir o `.cact` num repo Hugging Face seu e apontar
`ModelRepository.NEEDLE3` para ele.)

## ⚠️ Importante: confiança

O fine-tune **local** não treina o head de confiança, então o `confidence` volta
`None`. Hoje isso não muda o rumo da ação: o `CallResolver` **não usa confiança** —
se o engine pediu a ferramenta, ela executa (só dedupe e resolução de conflito).

Se você quiser o `confidence` calibrado de volta, existe o caminho de
**plataforma** (`needle platform finetune …`): treina o modelo inteiro em 2-bit
com o head junto, mas **envia seus dados para os GPUs da Cactus** — o que fere a
privacidade. Recomendo ficar no local.

## Dicas

- 5 tools ou menos por "superfície" rende mais que 10 de uma vez (ver §14).
- Aumente variações de polaridade ("liga/desliga/acende/apaga") e de número.
- Recusas (`answers: []`) são tão importantes quanto as chamadas.
