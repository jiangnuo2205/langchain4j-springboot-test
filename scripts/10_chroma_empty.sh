#!/usr/bin/env bash
set -euo pipefail

# 清空该服务chroma数据库

# 1. 停掉这个服务（找到运行 chroma 的终端按 Ctrl+C，或 kill 进程）
ps aux | grep chroma        # 查进程
lsof -nP -iTCP:8020 -sTCP:LISTEN
# kill <PID>                  # 杀掉

# 2. 删掉这一个服务的数据目录（只影响这个服务）
# rm -rf /Volumes/G/rag-file/langchain4j-springboot-test-rag-bge-m3-20260410/chroma-data

# 3. 重启服务（会自动创建空目录）
# ./start-chroma.sh

# 4. 跑你的 reindex 脚本