#!/usr/bin/env bash
set -euo pipefail

CASES_FILE="${1:-eval/cases.v3.docIdFilename.jsonl}"
BASE_URL="${BASE_URL:-http://localhost:8090}"
K_DEFAULT="${K_DEFAULT:-10}"

total=0
non_refusal_total=0
hit=0

refusal_total=0
refusal_ok=0

echo "Using BASE_URL=$BASE_URL cases=$CASES_FILE k_default=$K_DEFAULT"
echo

while IFS= read -r line; do
  [ -z "$line" ] && continue

  id=$(echo "$line" | jq -r .id)
  q=$(echo "$line" | jq -r .q)
  shouldRefuse=$(echo "$line" | jq -r .shouldRefuse)
  goldDocId=$(echo "$line" | jq -r '.gold.docId // ""')
  k=$(echo "$line" | jq -r ".k // $K_DEFAULT")

  total=$((total+1))

  res=$(curl -sG "$BASE_URL/api/rag/search" \
    --data-urlencode "q=$q" \
    --data-urlencode "k=$k")

  got=$(echo "$res" | jq -r '.results|length')
  top1=$(echo "$res" | jq -r '.results[0].metadata.docId // ""')
  docIds=$(echo "$res" | jq -r '.results[].metadata.docId // empty')

  if [ "$shouldRefuse" = "true" ]; then
    refusal_total=$((refusal_total+1))
    # 简化：search 没结果 => 可视为“应拒答”命中
    if [ "$got" = "0" ]; then
      refusal_ok=$((refusal_ok+1))
      echo "[REFUSAL_OK]  $id got=$got q=$q"
    else
      echo "[REFUSAL_BAD] $id got=$got top1=$top1 q=$q"
    fi
    continue
  fi

  non_refusal_total=$((non_refusal_total+1))

  if [ -n "$goldDocId" ] && echo "$docIds" | grep -Fxq "$goldDocId"; then
    hit=$((hit+1))
    echo "[HIT]         $id got=$got gold=$goldDocId top1=$top1"
  else
    echo "[MISS]        $id got=$got gold=$goldDocId top1=$top1 q=$q"
  fi
done < "$CASES_FILE"

echo
echo "hit_rate=$(awk "BEGIN{print ($hit)/($non_refusal_total==0?1:$non_refusal_total)}") ($hit/$non_refusal_total)"
echo "refusal_acc=$(awk "BEGIN{print ($refusal_ok)/($refusal_total==0?1:$refusal_total)}") ($refusal_ok/$refusal_total)"
echo "total_cases=$total"