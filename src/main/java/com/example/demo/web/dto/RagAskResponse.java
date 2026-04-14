package com.example.demo.web.dto;

import java.util.List;
import java.util.Map;

public record RagAskResponse(
    String question,
    String answer,
    List<String> retrievedChunks,
    Map<String, Object> debug
) {
    /** Backward-compatible constructor without debug info. */
    public RagAskResponse(String question, String answer, List<String> retrievedChunks) {
        this(question, answer, retrievedChunks, null);
    }
}
