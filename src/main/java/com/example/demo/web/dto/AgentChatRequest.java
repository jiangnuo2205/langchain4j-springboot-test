package com.example.demo.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentChatRequest(
    @NotBlank(message = "message must not be blank")
    String message
) {}
