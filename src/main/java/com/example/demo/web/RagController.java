package com.example.demo.web;

import com.example.demo.service.RagService;
import com.example.demo.web.dto.RagAskRequest;
import com.example.demo.web.dto.RagAskResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        int count = ragService.reindex();
        return Map.of("chunksIndexed", count);
    }

    @PostMapping("/ask")
    public RagAskResponse ask(@Valid @RequestBody RagAskRequest req) {
        List<String> chunks = ragService.retrieve(req.question());
        String answer = ragService.ask(req.question());
        return new RagAskResponse(req.question(), answer, chunks);
    }
}
