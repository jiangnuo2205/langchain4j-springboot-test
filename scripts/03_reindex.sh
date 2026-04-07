#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8090}"
curl -s -X POST "$BASE_URL/api/rag/reindex" | jq