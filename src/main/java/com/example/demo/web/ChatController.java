package com.example.demo.web;

import com.example.demo.service.ChatService;
import com.example.demo.web.dto.ChatRequest;
import com.example.demo.web.dto.ChatResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ChatController {

  private final ChatService chatService;
  private final String modelName;

  public ChatController(ChatService chatService,
                        @Value("${dashscope.model:qwen-turbo}") String modelName) {
    this.chatService = chatService;
    this.modelName = modelName;
  }

  @PostMapping("/chat")
  public ChatResponse chat(@Valid @RequestBody ChatRequest req) {
    String output = chatService.chat(req.message());
    return new ChatResponse(modelName, req.message(), output);
  }

  /**
   * 流式接口（SSE）
   * 前端用 EventSource 或 fetch + ReadableStream 来消费都行。
   */
  @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter chatStream(@Valid @RequestBody ChatRequest req) {
    return chatService.chatStreamSse(req.message());
  }

  @GetMapping("/health")
  public String health() {
    return "ok";
  }
}