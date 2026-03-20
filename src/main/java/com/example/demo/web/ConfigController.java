package com.example.demo.web;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class ConfigController {

    private final Environment env;

    public ConfigController(Environment env) {
        this.env = env;
    }

    @GetMapping("/api/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new HashMap<>();
        m.put("activeProfiles", env.getActiveProfiles());
        m.put("serverPort", env.getProperty("server.port"));
        m.put("dashscope.model", env.getProperty("dashscope.model"));
        m.put("dashscope.temperature", env.getProperty("dashscope.temperature"));
        m.put("dashscope.apiKeyPresent", env.getProperty("dashscope.api-key") != null && !env.getProperty("dashscope.api-key").isBlank());
        return m;
    }
}