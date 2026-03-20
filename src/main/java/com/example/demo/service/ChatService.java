package com.example.demo.service;

import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatLanguageModel model;
    private final StreamingChatLanguageModel streamingModel;

    public ChatService(ChatLanguageModel model,
                       StreamingChatLanguageModel streamingModel) {
        this.model = model;
        this.streamingModel = streamingModel;
    }

    public String chat(String message) {
        long start = System.nanoTime();
        try {
            String output = model.generate(message);
            long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            log.info("llm.chat costMs={} inputChars={} outputChars={}",
                    costMs, safeLen(message), safeLen(output));
            return output;
        } catch (Exception e) {
            long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            log.error("llm.chat failed costMs={} inputChars={} err={}",
                    costMs, safeLen(message), e.toString());
            throw e;
        }
    }

    public SseEmitter chatStreamSse(String message) {
        SseEmitter emitter = new SseEmitter(0L);
        long start = System.nanoTime();

        CompletableFuture.runAsync(() -> {
            try {
                streamingModel.generate(message, new StreamingResponseHandler<AiMessage>() {

                    @Override
                    public void onNext(String token) {
                        try {
                            emitter.send(SseEmitter.event().name("token").data(token));
                        } catch (IOException e) {
                            // 客户端断开/网络错误会走到这里
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
                        String fullText = (response == null || response.content() == null) ? null : response.content().text();

                        log.info("llm.stream done costMs={} inputChars={} outputChars={}",
                                costMs, safeLen(message), safeLen(fullText));

                        try {
                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        } catch (IOException ignored) {
                        }
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
                        log.error("llm.stream failed costMs={} err={}", costMs, error.toString());
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