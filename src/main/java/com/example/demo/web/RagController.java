package com.example.demo.web;

import com.example.demo.service.RagService;
import com.example.demo.web.dto.RagAskRequest;
import com.example.demo.web.dto.RagAskResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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
     * <p>
     * When {@code rag.queryRewrite.enabled=true}, also applies query rewriting (Strategy B)
     * and returns a {@code rewriteDiagnostics} field in the response.
     */
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(name = "q") String question,
            @RequestParam(name = "k", defaultValue = "0") int k) {
        RagService.SearchResponse sr = ragService.searchWithDiagnostics(question, k > 0 ? k : null);
        log.info("rag.search question='{}' k={} results={} rewriteEnabled={} ruleRan={} llmRan={}",
                question, k, sr.results().size(),
                sr.rewriteDiagnostics().rewriteEnabled(),
                sr.rewriteDiagnostics().ruleExpansionRan(),
                sr.rewriteDiagnostics().llmExpansionRan());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", question);
        response.put("results", sr.results());
        response.put("rewriteDiagnostics", buildDiagnosticsMap(sr.rewriteDiagnostics()));
        return response;
    }

    private Map<String, Object> buildDiagnosticsMap(RagService.SearchDiagnostics d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rewriteEnabled", d.rewriteEnabled());
        m.put("ruleExpansionRan", d.ruleExpansionRan());
        m.put("llmExpansionRan", d.llmExpansionRan());
        if (d.llmProvider() != null) m.put("llmProvider", d.llmProvider());
        m.put("variantQueries", d.variantQueries());
        m.put("triggerReason", d.triggerReason());
        return m;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(name = "n", defaultValue = "10") int n) {
        Map<String, Object> s = ragService.stats(n);
        log.info("rag.stats called n={} chunks={} vectorDimMax={} estimatedVectorBytes={} estimatedTextBytesUtf8={} maxChunksPerFile={} batchSize={} skipHugeFiles={} maxChunksPerDoc={} lastEmbeddingDim={}",
                n, s.get("chunks"), s.get("vectorDimMax"), s.get("estimatedVectorBytes"), s.get("estimatedTextBytesUtf8"), s.get("maxChunksPerFile"), s.get("batchSize"), s.get("skipHugeFiles"), s.get("maxChunksPerDoc"), s.get("lastEmbeddingDim"));
        return s;
    }
}
