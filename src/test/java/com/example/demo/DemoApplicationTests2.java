package com.example.demo;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class DemoApplicationTests2 {

  ChatLanguageModel chatLanguageModel;


  @Test
  void contextLoads() {
  }

//  @Test
//    void testChatLanguageModel() {
//    String message = "Hello, how are you?";
//    String chat = chatLanguageModel.chat(message);
//    System.out.println("Response: " + chat.text());
//
//
//  }



}