package com.example.demo.web;

import com.example.demo.service.DemoAgent;
import com.example.demo.web.dto.AgentChatRequest;
import com.example.demo.web.dto.AgentChatResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final DemoAgent demoAgent;

    public AgentController(DemoAgent demoAgent) {
        this.demoAgent = demoAgent;
    }

    @PostMapping("/agent/chat")
    public AgentChatResponse agentChat(@Valid @RequestBody AgentChatRequest req) {
        String output = demoAgent.chat(req.message());
        return new AgentChatResponse(req.message(), output);
    }
}
