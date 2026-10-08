#!/usr/bin/env bash
# Baixa o engine nativo android-arm64 (needle, libneedle.a, needle.h) da Cactus.
# Os artefatos NÃO são versionados: são baixados sob demanda.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$HERE/app/src/main/cpp"

if ! command -v needle >/dev/null 2>&1; then
    echo "cactus-needle não encontrado; instalando…"
    python3 -m pip install -U cactus-needle
fi

echo "Baixando engine android-arm64 em $OUT…"
needle download android-arm64 --out "$OUT"

echo "OK:"
ls -la "$OUT/android-arm64"
