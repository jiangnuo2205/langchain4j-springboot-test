#!/usr/bin/env bash
set -euo pipefail

# 使用方法
#  . 08_search.sh "薪资等级有哪些" 10


BASE_URL="${BASE_URL:-http://localhost:8090}"
Q="${1:-}"
K="${2:-10}"

if [ -z "$Q" ]; then
  echo "Usage: $0 \"your question\" [k]"
  exit 1
fi

curl -sG "$BASE_URL/api/rag/search" \
  --data-urlencode "q=$Q" \
  --data-urlencode "k=$K" | jq -r '
    . as $root |
    ($root.results | length) as $n |
    ($root.results | map(.metadata.docId) | unique | length) as $uniqueDocs |
    ($root.results[0].score // 0) as $top1Score |
    ($root.results | map(.metadata.docId) | group_by(.) | map(length) | max // 0) as $maxDocCount |
    "count=\($n) uniqueDoc@k=\($uniqueDocs) top1Score=\($top1Score) dominance=\($maxDocCount)/\($n)",
    "",
    ($root.results | to_entries[] | "\(.key+1)\t\(.value.score)\t\(.value.metadata.docId)" )
  '
