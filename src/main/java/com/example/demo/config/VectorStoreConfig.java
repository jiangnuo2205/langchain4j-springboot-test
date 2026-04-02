package com.example.demo.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {

  private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

  @Bean
  @ConditionalOnProperty(name = "vector.store", havingValue = "inmemory", matchIfMissing = true)
  EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
    log.info("vector.store=inmemory (InMemoryEmbeddingStore)");
    return new InMemoryEmbeddingStore<>();
  }

  @Bean
  @ConditionalOnProperty(name = "vector.store", havingValue = "chroma")
  EmbeddingStore<TextSegment> chromaEmbeddingStore(
          @Value("${chroma.base-url:http://localhost:8000}") String baseUrl,
          @Value("${chroma.collection:rag-default}") String collectionName
  ) {
    log.info("vector.store=chroma baseUrl={} collection={}", baseUrl, collectionName);
    return ChromaEmbeddingStore.builder()
            .apiVersion(ChromaApiVersion.V2)
            .baseUrl(baseUrl)
            .collectionName(collectionName)
            .build();
  }
}
