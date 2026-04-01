package com.example.demo.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LangChain4jConfig {

  // ── DashScope ChatLanguageModel (default) ──────────────────────────────────

  @Bean
  @ConditionalOnProperty(name = "llm.provider", havingValue = "dashscope", matchIfMissing = true)
  ChatModel dashscopeChatLanguageModel(
          @Value("${dashscope.api-key}") String apiKey,
          @Value("${dashscope.model:qwen-turbo}") String modelName,
          @Value("${dashscope.temperature:0.7}") double temperature
  ) {
    return QwenChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature((float) temperature)
            .build();
  }

  @Bean
  @ConditionalOnProperty(name = "llm.provider", havingValue = "dashscope", matchIfMissing = true)
  StreamingChatModel dashscopeStreamingChatLanguageModel(
          @Value("${dashscope.api-key}") String apiKey,
          @Value("${dashscope.model:qwen-turbo}") String modelName,
          @Value("${dashscope.temperature:0.7}") double temperature
  ) {
    return QwenStreamingChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature((float) temperature)
            .build();
  }

  @Bean
  @ConditionalOnProperty(name = "embedding.provider", havingValue = "dashscope", matchIfMissing = true)
  EmbeddingModel dashscopeEmbeddingModel(
          @Value("${dashscope.api-key}") String apiKey,
          @Value("${dashscope.embedding-model:text-embedding-v3}") String modelName
  ) {
    return QwenEmbeddingModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .build();
  }

  // ── Ollama ChatLanguageModel ────────────────────────────────────────────────

  @Bean
  @ConditionalOnProperty(name = "llm.provider", havingValue = "ollama")
  ChatModel ollamaChatLanguageModel(
          @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
          @Value("${ollama.chat-model:qwen3:4b}") String modelName,
          @Value("${ollama.temperature:0.7}") double temperature,
          @Value("${ollama.timeout:60}") long timeoutSeconds
  ) {
    return OllamaChatModel.builder()
            .baseUrl(baseUrl)
            .modelName(modelName)
            .temperature(temperature)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build();
  }

  @Bean
  @ConditionalOnProperty(name = "llm.provider", havingValue = "ollama")
  StreamingChatModel ollamaStreamingChatLanguageModel(
          @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
          @Value("${ollama.chat-model:qwen3:4b}") String modelName,
          @Value("${ollama.temperature:0.7}") double temperature,
          @Value("${ollama.timeout:60}") long timeoutSeconds
  ) {
    return OllamaStreamingChatModel.builder()
            .baseUrl(baseUrl)
            .modelName(modelName)
            .temperature(temperature)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build();
  }

  @Bean
  @ConditionalOnProperty(name = "embedding.provider", havingValue = "ollama")
  EmbeddingModel ollamaEmbeddingModel(
          @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
          @Value("${ollama.embedding-model:nomic-embed-text}") String modelName,
          @Value("${ollama.timeout:60}") long timeoutSeconds
  ) {
    return OllamaEmbeddingModel.builder()
            .baseUrl(baseUrl)
            .modelName(modelName)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build();
  }
}
