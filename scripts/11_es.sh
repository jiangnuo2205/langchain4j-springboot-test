
# 确认端口
curl -s http://localhost:9200 | head

# 内容查询
curl -s "http://localhost:9200/_cat/indices?v"

# chunk查询 es结构含chunkId、content、metadata等字段
curl -s "http://localhost:9200/rag-chunks/_search?pretty" \
  -H 'Content-Type: application/json' \
  -d '{"size":1,"query":{"match_all":{}}}'

# 结果示例 BM25 精确检索
curl -s "http://localhost:9200/rag-chunks/_search?pretty" \
  -H 'Content-Type: application/json' \
  -d '{"size":5,"query":{"match":{"text":"资产化"}}}'

# 端口测试
curl -G -s "http://localhost:8090/api/rag/search" \
  --data-urlencode "q=资产化" \
  --data-urlencode "k=10" | jq .

# 结果示例 BM25 精确检索
curl -s "http://localhost:8090/api/rag/search?q=rag.minScore&k=10" | jq .

# 端口测试 简便
curl -G -s "http://localhost:8090/api/rag/search" \
  --data-urlencode "q=资产化" \
  --data-urlencode "k=10" | jq '.results[] | {sourceId, score, textPreview}'