package com.example.demo.web.dto;

import java.util.List;

public record RagAskResponse(
    String question,
    String answer,
    List<String> retrievedChunks
) {}
