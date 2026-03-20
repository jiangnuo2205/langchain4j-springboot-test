package com.example.demo.config;

import com.example.demo.service.DemoAgent;
import com.example.demo.tools.DemoTools;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiServiceConfig {

    @Bean
    public DemoAgent demoAgent(ChatLanguageModel chatLanguageModel, DemoTools demoTools) {
        return AiServices.builder(DemoAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(demoTools)
                .build();
    }
}
