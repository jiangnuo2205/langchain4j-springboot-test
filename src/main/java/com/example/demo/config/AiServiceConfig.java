package com.example.demo.config;

import com.example.demo.service.DemoAgent;
import com.example.demo.tools.DemoTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiServiceConfig {

    @Bean
    public DemoAgent demoAgent(ChatModel chatModel, DemoTools demoTools) {
        return AiServices.builder(DemoAgent.class)
                .chatModel(chatModel)
                .tools(demoTools)
                .build();
    }
}
