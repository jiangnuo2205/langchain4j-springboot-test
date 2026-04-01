package com.example.demo.web;

import com.example.demo.service.RagService;
import com.example.demo.web.dto.RagAskRequest;
import com.example.demo.web.dto.RagAskResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

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

    /**
     * Debug retrieval quality: returns topK matches with scores and metadata.
     * GET /api/rag/search?q=your+question&amp;k=5  (k overrides default topK when &gt; 0)
     */
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(name = "q") String question,
            @RequestParam(name = "k", defaultValue = "0") int k) {
        List<Map<String, Object>> results = ragService.retrieveWithScores(question, k > 0 ? k : null);
        log.info("rag.search question='{}' k={} results={}", question, k, results.size());
        return Map.of(
                "question", question,
                "results", results
        );
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(name = "n", defaultValue = "10") int n) {
        Map<String, Object> s = ragService.stats(n);
        log.info("rag.stats called n={} chunks={} vectorDimMax={} estimatedVectorBytes={} estimatedTextBytesUtf8={}",
                n, s.get("chunks"), s.get("vectorDimMax"), s.get("estimatedVectorBytes"), s.get("estimatedTextBytesUtf8"));
        return s;
    }
}
