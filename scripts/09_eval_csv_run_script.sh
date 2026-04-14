#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Output directory prefix: override via EVAL_OUT_DIR env var.
# Multiple runs with different env vars will write to separate output dirs.
# Example:
#   RAG_HYBRID_RRF_VECTOR_WEIGHT=0.7 RAG_HYBRID_RRF_BM25_WEIGHT=0.3 \
#   EVAL_OUT_DIR=eval/perf_vw07_bw03 \
#   ./scripts/09_eval_csv_run_script.sh
OUT_DIR="${EVAL_OUT_DIR:-eval/perf}"

python3 "$ROOT/eval/run_eval_csv.py" \
  "$ROOT/eval/eval_cases.v3.docIdFilename_Version2.jsonl" \
  "$ROOT/$OUT_DIR"


# 会输出：
# ${OUT_DIR}_report.csv
# ${OUT_DIR}_topk.csv


# 或者直接用 python3 eval/run_eval_csv.py eval/eval_cases.v3.docIdFilename_Version2.jsonl eval/perf

