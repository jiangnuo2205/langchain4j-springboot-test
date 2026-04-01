package com.example.demo.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_HISTORY_SIZE = 20;

    private final ChatModel model;
    private final StreamingChatModel streamingModel;
    private final Map<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();

    public ChatService(ChatModel model,
                       StreamingChatModel streamingModel) {
        this.model = model;
        this.streamingModel = streamingModel;
    }

    public String chat(String message) {
        long start = System.nanoTime();
        String output = model.chat(message);
        long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("llm.chat costMs={} inputChars={} outputChars={}",
                costMs, safeLen(message), safeLen(output));
        return output;
    }

    public String chatWithMemory(String sessionId, String message) {
        List<ChatMessage> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
        history.add(new UserMessage(message));

        long start = System.nanoTime();
        ChatResponse response = model.chat(history);
        long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        AiMessage aiMessage = response.aiMessage();
        history.add(aiMessage);

        // Trim history to avoid unbounded growth
        if (history.size() > MAX_HISTORY_SIZE) {
            List<ChatMessage> trimmed = new ArrayList<>(history.subList(history.size() - MAX_HISTORY_SIZE, history.size()));
            sessions.put(sessionId, trimmed);
        }

        String output = aiMessage.text();
        log.info("llm.chat.memory costMs={} sessionId={} historySize={} inputChars={} outputChars={}",
                costMs, sessionId, history.size(), safeLen(message), safeLen(output));
        return output;
    }

    public SseEmitter chatStreamSse(String message) {
        SseEmitter emitter = new SseEmitter(0L);

//        requestId：每次请求一个
//        tokenCount：onNext 次数
//        ttftMs：第一次 onNext 发生时间 - start
//        totalMs：onComplete/onError 时间 - start
//        首条 event 发一个 meta，前端可用于显示/识别
        String requestId = UUID.randomUUID().toString();
        long startNs = System.nanoTime();
        AtomicInteger tokenCount = new AtomicInteger(0);
        AtomicLong firstTokenNs = new AtomicLong(0);

        // 先发 meta，前端能立即知道这是哪个请求
        try {
            emitter.send(SseEmitter.event()
                    .name("meta")
                    .data(Map.of("requestId", requestId)));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        CompletableFuture.runAsync(() -> {
            try {
                streamingModel.chat(message, new StreamingChatResponseHandler() {

                    @Override
                    public void onPartialResponse(String token) {
                        int n = tokenCount.incrementAndGet();

                        long now = System.nanoTime();
                        if (firstTokenNs.compareAndSet(0, now)) {
                            long ttftMs = Duration.ofNanos(now - startNs).toMillis();
                            log.info("llm.stream ttftMs={} requestId={} inputChars={}", ttftMs, requestId, safeLen(message));
                        }

                        // 这条用 debug，避免太多
                        log.debug("llm.stream token requestId={} n={} tokenLen={}", requestId, n, safeLen(token));

                        try {
                            emitter.send(SseEmitter.event().name("token").data(token));
                        } catch (IOException e) {
                            log.info("llm.stream client disconnected requestId={} tokenCount={}", requestId, tokenCount.get());
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        long totalMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
                        String fullText = (response == null || response.aiMessage() == null) ? null : response.aiMessage().text();

                        log.info("llm.stream done totalMs={} requestId={} tokenCount={} outputChars={}",
                                totalMs, requestId, tokenCount.get(), safeLen(fullText));

                        log.info("llm.stream done totalMs={} requestId={} tokenCount={} outputChars={}",
                                totalMs, requestId, tokenCount.get(), safeLen(fullText));

                        try {
                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                            // 也可以把统计信息回传给前端
                            emitter.send(SseEmitter.event().name("stats").data(Map.of(
                                    "requestId", requestId,
                                    "tokenCount", tokenCount.get(),
                                    "totalMs", totalMs
                            )));
                        } catch (IOException ignored) {
                        }
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        long totalMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
                        log.error("llm.stream failed totalMs={} requestId={} tokenCount={} err={}",
                                totalMs, requestId, tokenCount.get(), error.toString());

                        try {
                            emitter.send(SseEmitter.event().name("error").data(Map.of(
                                    "requestId", requestId,
                                    "message", String.valueOf(error.getMessage())
                            )));
                        } catch (IOException ignored) {
                        }
                        emitter.completeWithError(error);
                    }
                });
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private static int safeLen(String s) {
        return s == null ? 0 : s.length();
    }
}