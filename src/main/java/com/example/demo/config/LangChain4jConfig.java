package com.example.demo.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChain4jConfig {

  @Bean
  ChatLanguageModel chatLanguageModel(
          @Value("${dashscope.api-key}") String apiKey,
          @Value("${dashscope.model:qwen-turbo}") String modelName,
          @Value("${dashscope.temperature:0.7}") double temperature
  ) {
    return QwenChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature((float)temperature)
            .build();
  }

  @Bean
  StreamingChatLanguageModel streamingChatLanguageModel(
          @Value("${dashscope.api-key}") String apiKey,
          @Value("${dashscope.model:qwen-turbo}") String modelName,
          @Value("${dashscope.temperature:0.7}") double temperature
  ) {
    return QwenStreamingChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature((float)temperature)
            .build();
  }

  @Bean
  EmbeddingModel embeddingModel(
          @Value("${dashscope.api-key}") String apiKey,
          @Value("${dashscope.embedding-model:text-embedding-v2}") String modelName
  ) {
    return QwenEmbeddingModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .build();
  }
}