package com.example.demo.web.dto;

public record ChatResponse(
    String model,
    String input,
    String output
) {}
