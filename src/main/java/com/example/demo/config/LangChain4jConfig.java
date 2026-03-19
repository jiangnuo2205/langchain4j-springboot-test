package com.example.demo.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.DashscopeChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChain4jConfig {

  @Bean
  ChatLanguageModel chatLanguageModel(
      @Value("${dashscope.api-key}") String apiKey,
      @Value("${dashscope.model}") String modelName,
      @Value("${dashscope.temperature:0.7}") double temperature
  ) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "DashScope API Key is missing. Please set env DASHSCOPE_API_KEY or property dashscope.api-key"
      );
    }

    return DashscopeChatModel.builder()
        .apiKey(apiKey)
        .modelName(modelName)
        .temperature(temperature)
        .build();
  }
}
