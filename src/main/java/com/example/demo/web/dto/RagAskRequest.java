package com.example.demo.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RagAskRequest(
    @NotBlank(message = "question must not be blank")
    String question
) {}
