#!/usr/bin/env python3
"""Gera o JSONL de fine-tune a partir dos pares em PT.

Le:
  - app/src/main/assets/tools.json  (schemas reais da Catarina)
  - training/pairs_pt.json          (query -> answers, em portugues)

Escreve:
  - training/data/catarina_pt.jsonl no formato do `needle finetune`:
    {"query": "...", "tools": [...], "answers": [...]}

Uso:
  python3 training/build_dataset.py [--out training/data/catarina_pt.jsonl]
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOOLS = ROOT / "app" / "src" / "main" / "assets" / "tools.json"
PAIRS = ROOT / "training" / "pairs_pt.json"


def slim_tools(raw: list[dict]) -> list[dict]:
    """Mantem so o que o formato de treino usa (sem `triggers`)."""
    out = []
    for tool in raw:
        entry = {"name": tool["name"], "parameters": tool["parameters"]}
        if "description" in tool:
            entry["description"] = tool["description"]
        out.append(entry)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "training" / "data" / "catarina_pt.jsonl"))
    args = ap.parse_args()

    tools = slim_tools(json.loads(TOOLS.read_text(encoding="utf-8")))
    pairs = json.loads(PAIRS.read_text(encoding="utf-8"))["pairs"]

    names = {tool["name"] for tool in tools}
    valid, refused = 0, 0
    lines = []
    for pair in pairs:
        for call in pair["answers"]:
            if call["name"] not in names:
                print(f"ERRO: {call['name']!r} nao existe em tools.json", file=sys.stderr)
                return 1
        if pair["answers"]:
            valid += 1
        else:
            refused += 1
        lines.append(json.dumps({"query": pair["query"], "tools": tools, "answers": pair["answers"]},
                                ensure_ascii=False))

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"{len(lines)} exemplos ({valid} com chamada, {refused} recusas) -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
