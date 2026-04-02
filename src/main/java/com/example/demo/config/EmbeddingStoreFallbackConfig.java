package com.example.demo.config;


import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class EmbeddingStoreFallbackConfig {
    @Bean
    @Primary
    EmbeddingStore<TextSegment> embeddingStoreFallback() {
        return new InMemoryEmbeddingStore<>();
    }
}