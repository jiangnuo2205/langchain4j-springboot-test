# RAG 简介

RAG（Retrieval-Augmented Generation）是一种结合检索和生成的 AI 技术。
它先从知识库中检索相关片段，再把这些片段作为上下文传递给大语言模型来生成回答。

主要优点：
- 减少幻觉（Hallucination）
- 支持私有/实时知识库
- 可追溯来源

## 核心流程

1. 文档分块（Chunking）
2. 嵌入向量（Embedding）
3. 向量存储（Vector Store）
4. 检索（Retrieval）
5. 增强生成（Augmented Generation）
