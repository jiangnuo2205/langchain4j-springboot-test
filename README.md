# LangChain4j + Spring Boot + DashScope Demo

A Spring Boot demo project integrating LangChain4j with Alibaba DashScope (Qwen models), demonstrating:

- Non-streaming and streaming (SSE) chat
- Chat memory via sessionId
- Tool calling (agent demo)
- Minimal RAG (Retrieval-Augmented Generation)

## Quick Start

### 1. Configure your API key

Create `src/main/resources/application-local.properties` (not committed):

```properties
dashscope.api-key=your-real-dashscope-api-key

# Optional: override model
# dashscope.model=qwen-plus

# Optional: RAG docs directory
# rag.docs.dir=/path/to/your/docs
# rag.chunk.maxChars=500
# rag.topK=3
```

### 2. Run with local profile

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Open http://localhost:8090 in your browser.

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/chat` | Chat with memory (sessionId) |
| POST | `/api/chat/stream` | Streaming chat (SSE) |
| POST | `/api/agent/chat` | Agent with tool calling |
| POST | `/api/rag/reindex` | Build/rebuild RAG index |
| POST | `/api/rag/ask` | RAG-based question answering |
| GET  | `/api/health` | Health check |
| GET  | `/api/config` | Active config info |

### Chat with Memory (`POST /api/chat`)

Request:
```json
{ "message": "你好，我叫张三", "sessionId": "optional-session-id" }
```

Response:
```json
{ "model": "qwen-turbo", "input": "...", "output": "...", "sessionId": "uuid" }
```

The `sessionId` is returned and should be passed on subsequent requests to maintain conversation history.

### Tool Calling (`POST /api/agent/chat`)

The `DemoAgent` has access to three tools:
- `sum(a, b)` – returns the sum of two integers
- `getTime()` – returns the current server date/time
- `getConfig(key)` – returns a Spring property value by key

Request:
```json
{ "message": "3 加 7 等于多少？" }
```

### RAG (`POST /api/rag/reindex` + `POST /api/rag/ask`)

1. Set `rag.docs.dir` in `application-local.properties` to a directory containing `.txt` or `.md` files.
2. Call `POST /api/rag/reindex` to build the embedding index.
3. Call `POST /api/rag/ask` with `{ "question": "..." }` to get a RAG-augmented answer.

#### RAG Properties

| Property | Default | Description |
|----------|---------|-------------|
| `rag.docs.dir` | _(empty)_ | Directory to scan for `.txt`/`.md` files |
| `rag.chunk.maxChars` | `500` | Max characters per text chunk |
| `rag.topK` | `3` | Number of top chunks to retrieve |
| `dashscope.embedding-model` | `text-embedding-v2` | Embedding model name |

## Running Tests

```bash
mvn test
```

Tests use `@ActiveProfiles("local")` and `src/test/resources/application-local.properties` with a dummy key so no real API calls are made.

To run tests with a real key:
```bash
DASHSCOPE_API_KEY=your-key mvn test
```
