#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RAW_DIR="${RAW_DIR:-$ROOT/raw}"
OUT_DIR="${OUT_DIR:-$ROOT/normalized}"

mkdir -p "$OUT_DIR"

echo "[convert] RAW_DIR=$RAW_DIR"
echo "[convert] OUT_DIR=$OUT_DIR"

python3 "$ROOT/tools/pdf_ocr_to_txt.py" "$RAW_DIR" "$OUT_DIR"
python3 "$ROOT/tools/xlsx_to_md.py" "$RAW_DIR" "$OUT_DIR"
python3 "$ROOT/tools/docx_to_md.py" "$RAW_DIR" "$OUT_DIR"

# 如果 raw 里本来就有 .md/.txt，直接复制进 normalized（可选）
find "$RAW_DIR" -type f \( -name "*.md" -o -name "*.txt" \) -print0 | while IFS= read -r -d '' f; do
  cp -f "$f" "$OUT_DIR/$(basename "$f")"
done

echo "[convert] done"