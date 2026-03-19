package com.example.demo.web;

import com.example.demo.web.dto.ChatRequest;
import com.example.demo.web.dto.ChatResponse;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatController {

  private final ChatLanguageModel model;
  private final String modelName;

  public ChatController(ChatLanguageModel model,
                        @Value("${dashscope.model}") String modelName) {
    this.model = model;
    this.modelName = modelName;
  }

  @PostMapping("/chat")
  public ChatResponse chat(@Valid @RequestBody ChatRequest req) {
    String output = model.generate(req.message());
    return new ChatResponse(modelName, req.message(), output);
  }

  @GetMapping("/health")
  public String health() {
    return "ok";
  }
}
