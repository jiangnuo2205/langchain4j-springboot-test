# Ollama 配置说明

Ollama 是一款本地运行大语言模型的工具，支持多种开源模型。

## 启动 Ollama

```bash
# 拉取嵌入模型
ollama pull nomic-embed-text

# 拉取对话模型
ollama pull qwen3:4b

# 启动服务（默认端口 11434）
ollama serve
```

## 在本项目中使用 Ollama

在 application-local.properties 中配置：

```properties
llm.provider=ollama
embedding.provider=ollama
ollama.base-url=http://localhost:11434
ollama.chat-model=qwen3:4b
ollama.embedding-model=nomic-embed-text
```
