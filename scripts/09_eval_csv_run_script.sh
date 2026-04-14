#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Allow overriding output directory via EVAL_OUT_DIR env var.
# Default: eval/perf  — override to keep separate results per weight run, e.g.:
#   EVAL_OUT_DIR=eval/perf_vw2 scripts/09_eval_csv_run_script.sh
OUT_DIR="${EVAL_OUT_DIR:-eval/perf}"

python3 "$ROOT/eval/run_eval_csv.py" \
  "$ROOT/eval/eval_cases.v3.docIdFilename_Version2.jsonl" \
  "$ROOT/$OUT_DIR"


# 会输出：
# eval/perf_report.csv   (or $OUT_DIR_report.csv)
# eval/perf_topk.csv     (or $OUT_DIR_topk.csv)


# 或者直接用 python3 eval/run_eval_csv.py eval/eval_cases.v3.docIdFilename_Version2.jsonl eval/perf

