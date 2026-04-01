package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies Spring context loads with InMemory vector store + Ollama providers.
 * Ollama beans are constructed lazily (connection is only made on first call),
 * so this test does NOT require a running Ollama instance.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "llm.provider=ollama",
        "embedding.provider=ollama",
        "vector.store=inmemory",
        "ollama.base-url=http://localhost:11434",
        "ollama.chat-model=qwen3:4b",
        "ollama.embedding-model=nomic-embed-text",
        "rag.docs.dir="
})
class ProviderInMemoryOllamaTest {

    @Test
    void contextLoads() {
        // Verifies that the Spring context starts correctly with
        // InMemory vector store and Ollama providers (no real Ollama connection needed).
    }
}
