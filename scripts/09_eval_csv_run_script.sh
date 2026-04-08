#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$ROOT/eval/run_eval_csv.py" \
  "$ROOT/eval/eval_cases.v3.docIdFilename_Version2.jsonl" \
  "$ROOT/eval/perf"


# 会输出：
# eval/perf_report.csv
# eval/perf_topk.csv


# 或者直接用 python3 eval/run_eval_csv.py eval/eval_cases.v3.docIdFilename_Version2.jsonl eval/perf

