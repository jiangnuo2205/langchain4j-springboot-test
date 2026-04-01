package com.example.demo.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {

  @Bean
  @ConditionalOnProperty(name = "vector.store", havingValue = "inmemory", matchIfMissing = true)
  EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
    return new InMemoryEmbeddingStore<>();
  }

  @Bean
  @ConditionalOnProperty(name = "vector.store", havingValue = "chroma")
  EmbeddingStore<TextSegment> chromaEmbeddingStore(
          @Value("${chroma.base-url:http://localhost:8000}") String baseUrl,
          @Value("${chroma.collection:rag-default}") String collectionName
  ) {
    return ChromaEmbeddingStore.builder()
            .baseUrl(baseUrl)
            .collectionName(collectionName)
            .build();
  }
}
