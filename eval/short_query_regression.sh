#!/usr/bin/env bash
# ── Short-Query Regression Runner ──────────────────────────────────────────────
# Runs a fixed list of short Chinese queries against /api/rag/search and prints
# the top-10 results for each query.
#
# Usage:
#   ./eval/short_query_regression.sh [BASE_URL] [K]
#
# Examples:
#   ./eval/short_query_regression.sh                        # defaults to localhost:8090, k=10
#   ./eval/short_query_regression.sh http://localhost:8090 10
#
# Output columns (per result):
#   rank | score | sourceId | textPreview (first 120 chars)
#
# The response also prints rewriteDiagnostics so you can verify that rule/LLM
# expansion fired correctly.
#
# Prerequisites: curl, jq
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

BASE_URL="${1:-http://localhost:8090}"
K="${2:-10}"

# ── Short-query list (edit here to add/remove test queries) ──────────────────
QUERIES=(
  "假期"
  "薪酬"
  "资产化"
  "绩效"
  "领导力"
)
# ──────────────────────────────────────────────────────────────────────────────

SEP="════════════════════════════════════════════════════════════════"

echo "Short-Query Regression Runner"
echo "BASE_URL : $BASE_URL"
echo "K        : $K"
echo "Queries  : ${#QUERIES[@]}"
echo ""

for q in "${QUERIES[@]}"; do
  echo "$SEP"
  echo "Query: [$q]"
  echo ""

  RAW=$(curl -sG "${BASE_URL}/api/rag/search" \
    --data-urlencode "q=${q}" \
    --data-urlencode "k=${K}")

  # Check for valid JSON
  if ! echo "$RAW" | jq -e . >/dev/null 2>&1; then
    echo "[ERROR] Non-JSON response: $(echo "$RAW" | head -c 200)"
    echo ""
    continue
  fi

  # Print rewrite diagnostics
  echo "Rewrite diagnostics:"
  echo "$RAW" | jq -r '
    .rewriteDiagnostics as $d |
    "  enabled       : \($d.rewriteEnabled)",
    "  ruleRan       : \($d.ruleExpansionRan)",
    "  llmRan        : \($d.llmExpansionRan)",
    "  triggerReason : \($d.triggerReason)",
    "  variants      : \($d.variantQueries | join(", "))"
  ' 2>/dev/null || echo "  (no rewriteDiagnostics field — rewriting disabled or older server)"
  echo ""

  # Print top-K results
  RESULT_COUNT=$(echo "$RAW" | jq '.results | length')
  echo "Results: $RESULT_COUNT"
  echo ""

  echo "$RAW" | jq -r '
    .results | to_entries[] |
    [ (.key + 1 | tostring),
      (.value.score | tostring | .[0:8]),
      (.value.sourceId // "-"),
      (.value.textPreview // "-" | gsub("\n"; " ") | .[0:120])
    ] | "  \(.[0] | ltrimstr("0") | if . == "" then "0" else . end). score=\(.[1])  \(.[2])\n     \(.[3])"
  ' 2>/dev/null || echo "  (no results)"

  echo ""
done

echo "$SEP"
echo "Done."
