package com.example.demo.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChain4jConfig {

  @Bean
  @ConditionalOnProperty(name = "dashscope.api-key")
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
}