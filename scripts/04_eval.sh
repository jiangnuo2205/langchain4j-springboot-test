#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CASES="${1:-$ROOT/eval/eval_cases.v3.docIdFilename_Version2.jsonl}"

bash "$ROOT/eval/run_eval.sh" "$CASES"

# chmod +x scripts/04_eval.sh scripts/09_eval_csv_run_script.sh
  #./scripts/04_eval.sh
  #./scripts/09_eval_csv_run_script.sh