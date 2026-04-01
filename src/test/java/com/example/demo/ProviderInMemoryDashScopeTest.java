package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies Spring context loads with InMemory vector store + DashScope providers
 * using a dummy API key (no real network calls).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "dashscope.api-key=test-dummy-key",
        "llm.provider=dashscope",
        "embedding.provider=dashscope",
        "vector.store=inmemory",
        "rag.docs.dir="
})
class ProviderInMemoryDashScopeTest {

    @Test
    void contextLoads() {
        // Verifies that the Spring context starts correctly with
        // InMemory vector store and DashScope providers.
    }
}
