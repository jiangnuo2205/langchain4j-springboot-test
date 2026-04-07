#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CASES="${1:-$ROOT/eval/cases.v3.docIdFilename.jsonl}"

bash "$ROOT/eval/run_eval.sh" "$CASES"