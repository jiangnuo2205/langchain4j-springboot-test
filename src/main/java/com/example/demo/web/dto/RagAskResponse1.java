package com.example.demo.web.dto;

import java.util.List;

public record RagAskResponse1(
        String question,
        String answer,
        List<String> retrievedChunks
) {}
