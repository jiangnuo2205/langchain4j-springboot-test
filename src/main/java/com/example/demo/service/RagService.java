package com.example.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Fallback key used in per-doc counts when a chunk has no docId metadata. */
    private static final String UNKNOWN_DOC_KEY = "<unknown>";
    /** ATX-style Markdown heading pattern (# Heading, ## Heading, …). */
    private static final java.util.regex.Pattern HEADING_PATTERN =
            java.util.regex.Pattern.compile("^#{1,6}\\s+.*");
    /** Separator used between overlap tail and the following chunk body. */
    private static final String OVERLAP_SEPARATOR = "\n\n";

    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final String docsDir;
    private final int chunkMaxChars;
    private final int chunkOverlapChars;
    private final int topK;
    private final double minScore;
    private final boolean rerankEnabled;
    private final int rerankTopN;
    private final String llmProvider;
    private final String embeddingProvider;
    private final String vectorStore;
    private final int maxChunksPerFile;
    private final int batchSize;
    private final boolean skipHugeFiles;
    private final int maxChunksPerDoc;

    // Hybrid retrieval (BM25 + vector)
    private final boolean hybridEnabled;
    private final int hybridRrfK;
    private final int hybridCandidateK;

    // Confidence gate for answer refusal
    private final double answerMinScore;

    /** Optional BM25 service; null when rag.bm25.enabled=false. */
    @Autowired(required = false)
    private Bm25IndexService bm25IndexService;

    /** Optional query rewrite service; always present but no-ops when disabled. */
    @Autowired(required = false)
    private QueryRewriteService queryRewriteService;

    // Captured during the first successful reindex; used in stats for observability.
    private volatile int lastEmbeddingDim = 0;

    // Metadata snapshot for /api/rag/stats (no heap dump needed)
    public record IndexedChunkMeta(
            String id,
            String sourcePath,
            int chunkIndex,
            int vectorDim,
            long estimatedVectorBytes,
            long estimatedTextBytesUtf8
    ) {}

    private volatile List<IndexedChunkMeta> indexedMetas = List.of();
    // Count of chunks currently in the store (may include persisted data for Chroma)
    private volatile int indexedCount = 0;

    public RagService(
            EmbeddingModel embeddingModel,
            ChatModel chatModel,
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("${rag.docs.dir:}") String docsDir,
            @Value("${rag.chunk.maxChars:500}") int chunkMaxChars,
            @Value("${rag.chunk.overlapChars:80}") int chunkOverlapChars,
            @Value("${rag.topK:3}") int topK,
            @Value("${rag.minScore:0.0}") double minScore,
            @Value("${rag.rerank.enabled:false}") boolean rerankEnabled,
            @Value("${rag.rerank.topN:2}") int rerankTopN,
            @Value("${llm.provider:dashscope}") String llmProvider,
            @Value("${embedding.provider:dashscope}") String embeddingProvider,
            @Value("${vector.store:inmemory}") String vectorStore,
            @Value("${rag.index.maxChunksPerFile}") int maxChunksPerFile,
            @Value("${rag.index.batchSize}") int batchSize,
            @Value("${rag.index.skipHugeFiles.enabled}") boolean skipHugeFiles,
            @Value("${rag.retrieve.maxChunksPerDoc:2}") int maxChunksPerDoc,
            @Value("${rag.hybrid.enabled:false}") boolean hybridEnabled,
            @Value("${rag.hybrid.rrf.k:60}") int hybridRrfK,
            @Value("${rag.hybrid.candidateK:50}") int hybridCandidateK,
            @Value("${rag.answer.minScore:0.35}") double answerMinScore

    ) {
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.embeddingStore = embeddingStore;
        this.docsDir = docsDir;
        this.chunkMaxChars = chunkMaxChars;
        this.chunkOverlapChars = chunkOverlapChars;
        this.topK = topK;
        this.minScore = minScore;
        this.rerankEnabled = rerankEnabled;
        this.rerankTopN = rerankTopN;
        this.llmProvider = llmProvider;
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
        this.maxChunksPerFile = maxChunksPerFile;
        this.batchSize = batchSize;
        this.skipHugeFiles = skipHugeFiles;
        this.maxChunksPerDoc = maxChunksPerDoc;
        this.hybridEnabled = hybridEnabled;
        this.hybridRrfK = hybridRrfK;
        this.hybridCandidateK = Math.min(hybridCandidateK, 50);
        this.answerMinScore = answerMinScore;
    }

    @PostConstruct
    void logConfig() {
        log.info("rag.config vectorStore={} embeddingProvider={} llmProvider={} docsDir={} hybridEnabled={} answerMinScore={}",
                vectorStore, embeddingProvider, llmProvider, docsDir, hybridEnabled, answerMinScore);
    }

    /** Stats: index state summary for observability without heap dumps. */
    public Map<String, Object> stats(int topN) {
        int n = Math.max(0, Math.min(topN, 200));
        List<IndexedChunkMeta> metas = this.indexedMetas;

        int chunks = indexedCount;
        int vectorDimMax = 0;
        long vectorBytes = 0L;
        long textBytes = 0L;

        for (IndexedChunkMeta m : metas) {
            vectorDimMax = Math.max(vectorDimMax, m.vectorDim());
            vectorBytes += m.estimatedVectorBytes();
            textBytes += m.estimatedTextBytesUtf8();
        }

        List<String> firstIds = metas.stream().limit(n).map(IndexedChunkMeta::id).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunks", chunks);
        result.put("vectorDimMax", vectorDimMax);
        result.put("estimatedVectorBytes", vectorBytes);
        result.put("estimatedTextBytesUtf8", textBytes);
        result.put("firstIds", firstIds);
        result.put("llmProvider", llmProvider);
        result.put("embeddingProvider", embeddingProvider);
        result.put("vectorStore", vectorStore);
        result.put("rerankEnabled", rerankEnabled);
        result.put("maxChunksPerFile", maxChunksPerFile);
        result.put("batchSize", batchSize);
        result.put("skipHugeFiles", skipHugeFiles);
        result.put("maxChunksPerDoc", maxChunksPerDoc);
        result.put("lastEmbeddingDim", lastEmbeddingDim);
        result.put("hybridEnabled", hybridEnabled);
        result.put("bm25Available", bm25IndexService != null);
        result.put("chunkOverlapChars", chunkOverlapChars);
        result.put("answerMinScore", answerMinScore);
        return result;
    }

    /**
     * Rebuild the index: scan docsDir for .txt and .md files, chunk content, embed and store.
     *
     * @return number of chunks indexed
     */
    public int reindex() {
        if (docsDir == null || docsDir.isBlank()) {
            log.warn("rag.docs.dir is not configured – nothing to index");
            indexedMetas = List.of();
            indexedCount = 0;
            return 0;
        }

        Path dir = Paths.get(docsDir);
        if (!Files.isDirectory(dir)) {
            log.warn("rag.docs.dir={} is not a directory", docsDir);
            indexedMetas = List.of();
            indexedCount = 0;
            return 0;
        }

        List<TextSegment> allChunks = new ArrayList<>();
        List<String> allChunkIds = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".txt") || name.endsWith(".md");
                    })
                    .sorted()
                    .forEach(file -> {
                        try {

                            log.info("rag.reindex processing filename={}", file.getFileName());
                            String content = Files.readString(file);
                            List<String> chunks = chunkText(content);
                            String fileName = dir.relativize(file).toString();

                            if (skipHugeFiles && chunks.size() > maxChunksPerFile) {
                                log.warn("rag.reindex skip huge file={} chunks={}", fileName, chunks.size());
                                return;
                            }

                            for (int i = 0; i < chunks.size(); i++) {
                                String chunk = chunks.get(i);
                                Metadata meta = Metadata.from(Map.of(
                                        "docId", fileName,
                                        "sourcePath", file.toString(),
                                        "chunkIndex", String.valueOf(i),
                                        "chunkStrategy", chunkOverlapChars > 0 ? "structural-overlap" : "structural"
                                ));
                                log.debug("rag.reindex chunk meta file={} chunkIndex={} meta={}", fileName, i, meta);
                                if (chunk == null || chunk.isBlank()) {
                                    log.warn("rag.reindex skip blank chunk file={} chunkIndex={}", fileName, i);
                                    continue;
                                }
                                allChunks.add(TextSegment.from(chunk, meta));
                                allChunkIds.add(fileName + "#chunk=" + i);
                                log.debug("rag.reindex chunk file={} chunkIndex={} chunkLen={}", fileName, i, chunk.length());
                            }
                            log.info("rag.reindex file={} chunks={}", fileName, chunks.size());
                        } catch (IOException e) {
                            log.error("rag.reindex failed to read file={} err={}", file, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("rag.reindex walk failed dir={} err={}", docsDir, e.getMessage());
            return 0;
        }

        if (allChunks.isEmpty()) {
            log.info("rag.reindex no chunks found in dir={}", docsDir);
            indexedMetas = List.of();
            indexedCount = 0;
            return 0;
        }

        // Embed all chunks in batches and store with stable IDs
        log.info("rag.reindex embedding start chunks={}", allChunks.size());

        //batchSize从配置文件获取
        int batchSize = this.batchSize;
        List<Embedding> embeddings = new ArrayList<>();
        for (int start = 0; start < allChunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, allChunks.size());
            List<TextSegment> batch = allChunks.subList(start, end);
            List<String> batchIds = allChunkIds.subList(start, end);

            Response<List<Embedding>> resp = embeddingModel.embedAll(batch);
            Embedding first = (resp.content() == null || resp.content().isEmpty()) ? null : resp.content().get(0);
            int dim = (first == null || first.vector() == null) ? 0 : first.vector().length;
            if (start == 0 && dim > 0) {
                this.lastEmbeddingDim = dim;
            }
            log.info("rag.reindex embedding batch range=[{}, {}) segments={} embeddings={} dim={}",
                    start, end, batch.size(), resp.content() == null ? -1 : resp.content().size(), dim);

            if (resp == null || resp.content() == null || resp.content().isEmpty()) {
                throw new IllegalStateException("EmbeddingModel.embedAll returned empty embeddings. Check embedding.provider and API key/model config.");
            }

            log.info("rag.reindex embedAll done start={} end={} batchSize={} embeddings={}",
                    start, end, batch.size(), resp == null ? "null" : (resp.content() == null ? "null" : resp.content().size()));
            log.info("finishreason={},tokenusage={}", resp == null ? "null" : resp.finishReason(), resp == null ? "null" : resp.tokenUsage());

            if (resp == null || resp.content() == null) {
                throw new IllegalStateException("embedAll returned null content, start=" + start + " end=" + end);
            }
            if (resp.content().isEmpty()) {
                throw new IllegalStateException("embedAll returned empty embeddings, batchSize=" + batch.size());
            }
            if (resp.content().size() != batch.size()) {
                throw new IllegalStateException("embedAll size mismatch: embeddings=" + resp.content().size()
                        + " segments=" + batch.size() + " start=" + start + " end=" + end);
            }
            embeddings.addAll(resp.content());

            try {
                // 关键：用带 id 的写入（下面这个方法名/签名以你当前 langchain4j 版本为准）
                embeddingStore.addAll(batchIds, resp.content(), batch);
            } catch (Exception e) {
                log.error("Chroma addAll failed start={} end={} firstId={} err={}",
                        start, end, batchIds.get(0), e.toString(), e);
                throw e; // fail-fast：让 /api/rag/reindex 返回 500
            }

            log.info("rag.reindex embedded+stored {} / {}", end, allChunks.size());
        }

        log.info("rag.reindex storing complete chunks={} embeddings处理块={}", allChunks.size(), embeddings.size());

        // BM25 indexing (when enabled)
        if (bm25IndexService != null) {
            log.info("rag.reindex bm25 indexing start chunks={}", allChunks.size());
            try {
                bm25IndexService.clearAndCreateIndex();
                bm25IndexService.bulkIndex(allChunks, allChunkIds);
                log.info("rag.reindex bm25 indexing done chunks={}", allChunks.size());
            } catch (Exception e) {
                log.error("rag.reindex bm25 indexing failed err={} – vector index is still complete", e.getMessage());
            }
        }

        // Post-reindex persistence sanity check: search using first chunk to verify embeddings are retrievable
        try {
            Embedding sampleEmbedding = embeddingModel.embed(allChunks.get(0)).content();
            EmbeddingSearchRequest sampleReq = EmbeddingSearchRequest.builder()
                    .queryEmbedding(sampleEmbedding)
                    .maxResults(1)
                    .minScore(0.0)
                    .build();
            int matchCount = embeddingStore.search(sampleReq).matches().size();
            log.info("rag.reindex sanityCheck matchCount={} vectorStore={}", matchCount, vectorStore);
            if (matchCount == 0) {
                log.warn("rag.reindex sanityCheck returned 0 matches – embeddings may not be persisted vectorStore={}", vectorStore);
            }
        } catch (Exception ex) {
            log.warn("rag.reindex sanityCheck failed err={}", ex.getMessage());
        }

        // Build metadata snapshot for stats
        List<IndexedChunkMeta> metas = new ArrayList<>(allChunks.size());
        for (int i = 0; i < allChunks.size(); i++) {
            String id = allChunkIds.get(i);
            String text = allChunks.get(i).text();
            String sourcePath = allChunks.get(i).metadata().getString("sourcePath");

            Embedding emb = (embeddings != null && i < embeddings.size()) ? embeddings.get(i) : null;
            int dim = (emb == null || emb.vector() == null) ? 0 : emb.vector().length;
            long vecBytes = (long) dim * 4L;
            long txtBytes = (text == null) ? 0L : text.getBytes(StandardCharsets.UTF_8).length;
            metas.add(new IndexedChunkMeta(id, sourcePath != null ? sourcePath : "", i, dim, vecBytes, txtBytes));
        }
        this.indexedMetas = List.copyOf(metas);
        this.indexedCount = allChunks.size();

        log.info("rag.reindex done dir={} chunks={} embeddingProvider={} vectorStore={}",
                docsDir, allChunks.size(), embeddingProvider, vectorStore);
        return allChunks.size();
    }

    /**
     * Retrieve topK chunks most similar to the question, returning text only.
     */
    public List<String> retrieve(String question) {
        List<Map<String, Object>> results = retrieveWithScores(question, null);
        return results.stream()
                .map(r -> (String) r.get("text"))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Retrieve topK chunks with scores and metadata (using configured topK).
     * Each result map contains: sourceId, score, text, metadata.
     */
    public List<Map<String, Object>> retrieveWithScores(String question) {
        return retrieveWithScores(question, null);
    }

    /**
     * Retrieve chunks with scores and metadata, optionally overriding maxResults.
     * When {@code rag.retrieve.maxChunksPerDoc > 0}, post-processing diversification
     * is applied: no more than {@code maxChunksPerDoc} chunks from the same docId are
     * included in the final results, preventing a single document from dominating topK.
     * <p>
     * When {@code rag.hybrid.enabled=true} and BM25 is available, delegates to
     * {@link #retrieveWithScoresHybrid} for Reciprocal Rank Fusion.
     *
     * @param question   the query
     * @param maxResults override for topK (null = use configured topK)
     */
    public List<Map<String, Object>> retrieveWithScores(String question, Integer maxResults) {
        if (hybridEnabled && bm25IndexService != null) {
            return retrieveWithScoresHybrid(question, maxResults);
        }
        return retrieveVectorOnly(question, maxResults);
    }

    // ── Query-rewrite diagnostics records ─────────────────────────────────────

    /**
     * Diagnostics for the query rewriting step in a search response.
     *
     * @param rewriteEnabled   whether query rewriting is on
     * @param ruleExpansionRan whether rule-based expansion was applied
     * @param llmExpansionRan  whether LLM multi-query was triggered
     * @param llmProvider      LLM provider used for rewriting ({@code null} if not triggered)
     * @param variantQueries   all query variants used for retrieval (original always first)
     * @param triggerReason    human-readable reason string
     */
    public record SearchDiagnostics(
            boolean rewriteEnabled,
            boolean ruleExpansionRan,
            boolean llmExpansionRan,
            String llmProvider,
            List<String> variantQueries,
            String triggerReason
    ) {}

    /**
     * Extended search response that includes both retrieved results and rewrite diagnostics.
     *
     * @param results            ranked list of retrieved chunks (same structure as {@link #retrieveWithScores})
     * @param rewriteDiagnostics diagnostics about query rewriting
     */
    public record SearchResponse(
            List<Map<String, Object>> results,
            SearchDiagnostics rewriteDiagnostics
    ) {}

    /**
     * Search with optional query rewriting (Strategy B).
     * <p>
     * When {@code rag.queryRewrite.enabled=true}:
     * <ol>
     *   <li>Applies rule-based expansion for short queries.</li>
     *   <li>Runs retrieval for each variant and fuses results via multi-query RRF.</li>
     *   <li>If the fused top score is below {@code rag.queryRewrite.llmTrigger.minTopScore},
     *       additionally triggers LLM multi-query and re-fuses.</li>
     * </ol>
     * When rewriting is disabled, falls back to {@link #retrieveWithScores}.
     *
     * @param question   the query text
     * @param maxResults override for topK (null = use configured topK)
     */
    public SearchResponse searchWithDiagnostics(String question, Integer maxResults) {
        int limit = maxResults != null ? maxResults : topK;

        if (queryRewriteService == null || !queryRewriteService.isEnabled()) {
            List<Map<String, Object>> results = retrieveWithScores(question, maxResults);
            SearchDiagnostics diag = new SearchDiagnostics(
                    false, false, false, null, List.of(question), "disabled");
            return new SearchResponse(results, diag);
        }

        // ── Phase 1: Rule expansion ────────────────────────────────────────────
        QueryRewriteService.RewriteResult phase1 = queryRewriteService.ruleOnlyRewrite(question);
        List<String> allVariants = new ArrayList<>(phase1.variantQueries());

        // ── Phase 2: Retrieve for all phase-1 variants and fuse ───────────────
        // Use a generous candidateK so fusion has enough raw candidates.
        int candidateK = Math.max(hybridCandidateK, limit * 3);
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Map<String, Object>> docData = new LinkedHashMap<>();
        Map<String, Set<String>> docVariants = new LinkedHashMap<>();

        for (String variant : allVariants) {
            List<Map<String, Object>> variantResults = retrieveWithScores(variant, candidateK);
            for (int rank = 0; rank < variantResults.size(); rank++) {
                Map<String, Object> r = variantResults.get(rank);
                String id = (String) r.get("sourceId");
                if (id == null) continue;
                double rrf = 1.0 / (hybridRrfK + rank + 1);
                rrfScores.merge(id, rrf, Double::sum);
                docData.putIfAbsent(id, r);
                docVariants.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(variant);
            }
        }

        // Fused top score after phase-1
        double topScore = rrfScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        // ── Phase 3: Maybe trigger LLM multi-query ────────────────────────────
        boolean llmRan = false;
        String llmProvider = null;
        List<String> llmVariants = queryRewriteService.maybeLlmRewrite(question, topScore);

        if (!llmVariants.isEmpty()) {
            llmRan = true;
            llmProvider = queryRewriteService.getPrimaryProviderName();
            for (String lv : llmVariants) {
                if (!allVariants.contains(lv)) {
                    allVariants.add(lv);
                    List<Map<String, Object>> lvResults = retrieveWithScores(lv, candidateK);
                    for (int rank = 0; rank < lvResults.size(); rank++) {
                        Map<String, Object> r = lvResults.get(rank);
                        String id = (String) r.get("sourceId");
                        if (id == null) continue;
                        double rrf = 1.0 / (hybridRrfK + rank + 1);
                        rrfScores.merge(id, rrf, Double::sum);
                        docData.putIfAbsent(id, r);
                        docVariants.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(lv);
                    }
                }
            }
        }

        // ── Sort by fused RRF score ────────────────────────────────────────────
        List<String> sortedIds = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        // ── Build merged list with per-result diagnostics ─────────────────────
        List<Map<String, Object>> merged = new ArrayList<>(sortedIds.size());
        for (String id : sortedIds) {
            Map<String, Object> r = new LinkedHashMap<>(docData.get(id));
            r.put("score", rrfScores.get(id));
            Set<String> matchedVars = docVariants.get(id);
            // Only annotate when multiple variants contributed (extra signal)
            if (matchedVars != null && matchedVars.size() > 1) {
                r.put("matchedVariants", new ArrayList<>(matchedVars));
            }
            merged.add(r);
        }

        log.debug("rag.search rewrite variants={} merged={} topScore={} llmRan={}",
                allVariants.size(), merged.size(), topScore, llmRan);

        // ── Apply maxChunksPerDoc diversification ─────────────────────────────
        List<Map<String, Object>> results = applyMaxChunksPerDoc(merged, limit);

        boolean ruleRan = phase1.ruleExpansionRan();
        String triggerReason = (ruleRan && llmRan) ? "rule_expansion+llm"
                : (ruleRan ? "rule_expansion" : "original");

        SearchDiagnostics diag = new SearchDiagnostics(
                true, ruleRan, llmRan, llmProvider,
                Collections.unmodifiableList(allVariants), triggerReason);

        return new SearchResponse(results, diag);
    }

    /** Apply maxChunksPerDoc diversification and limit results to {@code limit}. */
    private List<Map<String, Object>> applyMaxChunksPerDoc(List<Map<String, Object>> merged, int limit) {
        if (maxChunksPerDoc <= 0 || merged.isEmpty()) {
            return merged.subList(0, Math.min(limit, merged.size()));
        }
        Map<String, Integer> docCounts = new LinkedHashMap<>();
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> r : merged) {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) r.get("metadata");
            String docId = meta != null ? (String) meta.get("docId") : null;
            String key = docId != null ? docId : UNKNOWN_DOC_KEY;
            int count = docCounts.getOrDefault(key, 0);
            if (count < maxChunksPerDoc) {
                docCounts.put(key, count + 1);
                filtered.add(r);
                if (filtered.size() >= limit) break;
            }
        }
        return filtered;
    }

    private List<Map<String, Object>> retrieveVectorOnly(String question, Integer maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(TextSegment.from(question)).content();

        int limit = maxResults != null ? maxResults : topK;

        // When diversification is enabled, fetch extra candidates so filtering still
        // yields `limit` results even if some docs are over-represented in the raw ranking.
        // The multiplier (maxChunksPerDoc) ensures we have enough raw candidates; the
        // minimum fetch of 100 avoids very small fetch windows for tiny topK values.
        int fetchSize = (maxChunksPerDoc > 0)
                ? Math.min(limit * maxChunksPerDoc, Math.max(limit * 10, 100))
                : limit;

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(fetchSize)
                .minScore(minScore)
                .build();

        List<EmbeddingMatch<TextSegment>> allMatches = embeddingStore.search(request).matches();
        List<EmbeddingMatch<TextSegment>> matches;

        if (maxChunksPerDoc > 0 && !allMatches.isEmpty()) {
            Map<String, Integer> docCounts = new LinkedHashMap<>();
            List<EmbeddingMatch<TextSegment>> filtered = new ArrayList<>();
            int skipped = 0;

            for (EmbeddingMatch<TextSegment> m : allMatches) {
                String docId = (m.embedded() != null && m.embedded().metadata() != null)
                        ? m.embedded().metadata().getString("docId")
                        : null;
                String key = docId != null ? docId : UNKNOWN_DOC_KEY;
                int count = docCounts.getOrDefault(key, 0);
                if (count < maxChunksPerDoc) {
                    docCounts.put(key, count + 1);
                    filtered.add(m);
                    if (filtered.size() >= limit) break;
                } else {
                    skipped++;
                }
            }

            log.debug("rag.retrieve diversify uniqueDocIds={} skipped={} selected={} maxChunksPerDoc={}",
                    docCounts.size(), skipped, filtered.size(), maxChunksPerDoc);
            matches = filtered;
        } else {
            matches = allMatches;
        }

        List<Map<String, Object>> results = new ArrayList<>();

        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            if (match.embedded() == null) continue;

            TextSegment seg = match.embedded();
            Metadata meta = seg.metadata();

            String docId = meta != null ? meta.getString("docId") : null;
            String sourcePath = meta != null ? meta.getString("sourcePath") : null;
            String chunkIndex = meta != null ? meta.getString("chunkIndex") : null;
            String chunkStrategy = meta != null ? meta.getString("chunkStrategy") : null;

            String sourceId = (docId != null)
                    ? docId + (chunkIndex != null ? "#chunk=" + chunkIndex : "")
                    : "chunk-" + i;

            String text = seg.text();
            String preview = (text != null && text.length() > 200) ? text.substring(0, 200) + "…" : text;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sourceId", sourceId);
            entry.put("score", match.score());
            entry.put("textPreview", preview);
            entry.put("text", text);
            if (meta != null) {
                Map<String, Object> metaMap = new LinkedHashMap<>();
                if (docId != null) metaMap.put("docId", docId);
                if (sourcePath != null) metaMap.put("sourcePath", sourcePath);
                if (chunkIndex != null) metaMap.put("chunkIndex", chunkIndex);
                if (chunkStrategy != null) metaMap.put("chunkStrategy", chunkStrategy);
                entry.put("metadata", metaMap);
            }
            results.add(entry);
        }
        return results;
    }

    // ── Hybrid retrieval with RRF fusion ───────────────────────────────────────

    /**
     * Hybrid retrieval: combines vector (Chroma) and BM25 (Elasticsearch) candidates
     * using Reciprocal Rank Fusion (RRF).
     *
     * <p>RRF score formula per document: {@code sum_over_rankers( 1 / (k + rank) )}
     * where {@code k = rag.hybrid.rrf.k} (default 60) dampens the influence of
     * highly-ranked documents.
     *
     * @param question   the query text
     * @param maxResults override for topK (null = use configured topK)
     */
    private List<Map<String, Object>> retrieveWithScoresHybrid(String question, Integer maxResults) {
        int limit = maxResults != null ? maxResults : topK;
        int candidateK = hybridCandidateK;
        int k = hybridRrfK;

        // ── 1. Vector candidates ───────────────────────────────────────────────
        List<Map<String, Object>> vectorResults = retrieveVectorOnly(question, candidateK);

        // ── 2. BM25 candidates (with graceful fallback) ────────────────────────
        List<Bm25IndexService.BM25Hit> bm25Results;
        try {
            bm25Results = bm25IndexService.search(question, candidateK);
        } catch (Exception e) {
            log.warn("rag.hybrid bm25 search failed, falling back to vector-only err={}", e.getMessage());
            return vectorResults.subList(0, Math.min(limit, vectorResults.size()));
        }

        // ── 3. RRF fusion ──────────────────────────────────────────────────────
        // Map: sourceId -> accumulated RRF score
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        // Map: sourceId -> result data (from whichever retriever saw it first)
        Map<String, Map<String, Object>> docData = new LinkedHashMap<>();

        // Score from vector ranking
        for (int i = 0; i < vectorResults.size(); i++) {
            Map<String, Object> r = vectorResults.get(i);
            String sourceId = (String) r.get("sourceId");
            if (sourceId == null) continue;
            double rrf = 1.0 / (k + i + 1);
            rrfScores.merge(sourceId, rrf, Double::sum);
            docData.putIfAbsent(sourceId, r);
        }

        // Score from BM25 ranking
        for (int i = 0; i < bm25Results.size(); i++) {
            Bm25IndexService.BM25Hit hit = bm25Results.get(i);
            String chunkId = hit.chunkId();
            double rrf = 1.0 / (k + i + 1);
            rrfScores.merge(chunkId, rrf, Double::sum);
            if (!docData.containsKey(chunkId)) {
                // Build a result map from the BM25 hit (chunk was not in vector results)
                docData.put(chunkId, buildResultFromBm25Hit(hit));
            }
        }

        // ── 4. Sort by fused RRF score ─────────────────────────────────────────
        List<String> sortedIds = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        // ── 5. Build merged list (replace individual scores with RRF score) ────
        List<Map<String, Object>> merged = new ArrayList<>(sortedIds.size());
        for (String id : sortedIds) {
            Map<String, Object> r = new LinkedHashMap<>(docData.get(id));
            r.put("score", rrfScores.get(id));
            merged.add(r);
        }

        log.debug("rag.hybrid vectorCandidates={} bm25Candidates={} merged={} limit={}",
                vectorResults.size(), bm25Results.size(), merged.size(), limit);

        // ── 6. Apply maxChunksPerDoc diversification ───────────────────────────
        if (maxChunksPerDoc > 0 && !merged.isEmpty()) {
            Map<String, Integer> docCounts = new LinkedHashMap<>();
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> r : merged) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) r.get("metadata");
                String docId = meta != null ? (String) meta.get("docId") : null;
                String key = docId != null ? docId : UNKNOWN_DOC_KEY;
                int count = docCounts.getOrDefault(key, 0);
                if (count < maxChunksPerDoc) {
                    docCounts.put(key, count + 1);
                    filtered.add(r);
                    if (filtered.size() >= limit) break;
                }
            }
            return filtered;
        }

        return merged.subList(0, Math.min(limit, merged.size()));
    }

    /** Build a result map from a BM25 hit, matching the structure from vector retrieval. */
    private Map<String, Object> buildResultFromBm25Hit(Bm25IndexService.BM25Hit hit) {
        String text = hit.text();
        String preview = (text != null && text.length() > 200) ? text.substring(0, 200) + "…" : text;
        String sourceId = (hit.docId() != null)
                ? hit.docId() + "#chunk=" + hit.chunkIndex()
                : hit.chunkId();

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sourceId", sourceId);
        entry.put("score", hit.score());
        entry.put("textPreview", preview);
        entry.put("text", text);
        Map<String, Object> metaMap = new LinkedHashMap<>();
        if (hit.docId() != null) metaMap.put("docId", hit.docId());
        if (hit.sourcePath() != null) metaMap.put("sourcePath", hit.sourcePath());
        metaMap.put("chunkIndex", String.valueOf(hit.chunkIndex()));
        metaMap.put("chunkStrategy", "bm25");
        entry.put("metadata", metaMap);
        return entry;
    }

    /**
     * Retrieve relevant chunks and answer the question using the chat model.
     * <p>
     * Applies a confidence gate: if retrieval returns no results or the top chunk
     * score is below {@code rag.answer.minScore}, a refusal message is returned
     * instead of calling the LLM with low-quality context.
     * <p>
     * Optionally applies LLM-based reranking when rag.rerank.enabled=true.
     */
    public String ask(String question) {
        List<Map<String, Object>> results = retrieveWithScores(question);

        if (results.isEmpty()) {
            log.info("rag.ask confidence gate: no retrieval results question='{}'", question);
            return "抱歉，我在知识库中没有找到与您问题相关的内容。请尝试换个方式提问，或确认该问题是否在知识库范围内。\n\n"
                    + "Sorry, no relevant content was found in the knowledge base for your question. "
                    + "Please try rephrasing or check whether the topic is covered.";
        }

        double topScore = (double) results.get(0).get("score");
        if (answerMinScore > 0.0 && topScore < answerMinScore) {
            log.info("rag.ask confidence gate triggered topScore={} answerMinScore={} question='{}'",
                    topScore, answerMinScore, question);
            return String.format(
                    "抱歉，我没有找到足够可信的相关内容来回答您的问题（最高相关度 %.3f，阈值 %.3f）。"
                            + "建议您换一种表达方式，或者这个问题可能超出了知识库的范围。\n\n"
                            + "Sorry, the retrieved content confidence (%.3f) is below the required threshold (%.3f). "
                            + "Please rephrase your question or check if the topic is in scope.",
                    topScore, answerMinScore, topScore, answerMinScore);
        }

        List<Map<String, Object>> contextResults = rerankEnabled
                ? rerank(question, results)
                : results;

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < contextResults.size(); i++) {
            String text = (String) contextResults.get(i).get("text");
            if (text != null) {
                context.append("[").append(i + 1).append("] ").append(text).append("\n\n");
            }
        }

        String prompt = "Use the following context to answer the question.\n\n"
                + "Context:\n" + context
                + "Question: " + question;

        return chatModel.chat(prompt);
    }

    /**
     * LLM-based reranking: ask chat model to select the most relevant chunk IDs.
     * Falls back to original results if reranking fails.
     */
    private List<Map<String, Object>> rerank(String question, List<Map<String, Object>> candidates) {
        if (candidates.isEmpty()) return candidates;

        int n = Math.min(rerankTopN, candidates.size());

        // Build a numbered list for the model to choose from
        StringBuilder sb = new StringBuilder();
        sb.append("Given the question: \"").append(question).append("\"\n\n");
        sb.append("Below are text chunks. Return ONLY a JSON array of the top ").append(n)
                .append(" most relevant chunk numbers (1-based), e.g. [1,3].\n\n");

        for (int i = 0; i < candidates.size(); i++) {
            String preview = (String) candidates.get(i).get("textPreview");
            sb.append("[").append(i + 1).append("] ").append(preview).append("\n\n");
        }
        sb.append("JSON array:");

        try {
            String response = chatModel.chat(sb.toString());
            // Extract JSON array from response
            int start = response.indexOf('[');
            int end = response.lastIndexOf(']');
            if (start < 0 || end <= start) {
                log.warn("rag.rerank could not parse response, using original order");
                return candidates.subList(0, n);
            }
            String json = response.substring(start, end + 1);
            List<Integer> indices = MAPPER.readValue(json, new TypeReference<>() {});

            List<Map<String, Object>> reranked = new ArrayList<>();
            for (int idx : indices) {
                if (idx >= 1 && idx <= candidates.size()) {
                    reranked.add(candidates.get(idx - 1));
                }
                if (reranked.size() >= n) break;
            }
            if (reranked.isEmpty()) return candidates.subList(0, n);

            log.info("rag.rerank question='{}' selected={}", question, indices);
            return reranked;
        } catch (Exception e) {
            log.warn("rag.rerank failed, using original order err={}", e.getMessage());
            return candidates.subList(0, n);
        }
    }

    /**
     * Split text into chunks with structural awareness and optional overlap.
     * <p>
     * Strategy (in priority order):
     * <ol>
     *   <li>Split on Markdown headings ({@code #}, {@code ##}, etc.) to preserve structure.</li>
     *   <li>Within each section, split on blank lines (paragraph boundaries).</li>
     *   <li>For paragraphs exceeding {@code chunkMaxChars}, split on sentence boundaries
     *       (Chinese: {@code 。！？}; English: {@code .!?}) via {@link #splitLongText}.</li>
     *   <li>Apply {@code chunkOverlapChars} tail overlap between consecutive chunks.</li>
     * </ol>
     */
    private List<String> chunkText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        // Step 1: Split on heading boundaries to keep sections together
        List<String> sections = splitOnHeadings(text);

        List<String> rawChunks = new ArrayList<>();

        for (String section : sections) {
            // Step 2 (per section): split on blank lines to get paragraph candidates
            String[] paragraphs = section.split("\\n\\n+");
            StringBuilder current = new StringBuilder();

            for (String para : paragraphs) {
                String trimmed = para.strip();
                if (trimmed.isEmpty()) continue;

                if (current.length() > 0 && current.length() + trimmed.length() + 2 > chunkMaxChars) {
                    rawChunks.add(current.toString().strip());
                    current = new StringBuilder();
                }

                // Step 3: Long paragraphs get sentence-split
                if (trimmed.length() > chunkMaxChars) {
                    if (current.length() > 0) {
                        rawChunks.add(current.toString().strip());
                        current = new StringBuilder();
                    }
                    splitLongText(trimmed, chunkMaxChars, rawChunks);
                } else {
                    if (current.length() > 0) current.append("\n\n");
                    current.append(trimmed);
                }
            }

            if (current.length() > 0) {
                rawChunks.add(current.toString().strip());
            }
        }

        // Remove any blank chunks produced by edge cases
        rawChunks.removeIf(String::isBlank);

        // Step 4: Apply overlap between consecutive chunks
        return applyOverlap(rawChunks, chunkOverlapChars);
    }

    /**
     * Split text on Markdown heading lines (lines starting with {@code #}).
     * The heading line is kept as the first line of each section.
     */
    private List<String> splitOnHeadings(String text) {
        String[] lines = text.split("\\n", -1);
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            // Detect ATX-style headings using pre-compiled pattern
            if (HEADING_PATTERN.matcher(line).matches() && current.length() > 0) {
                sections.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) {
            sections.add(current.toString());
        }
        return sections.isEmpty() ? List.of(text) : sections;
    }

    /**
     * Prepend the tail of the previous chunk (up to {@code overlapChars} characters)
     * to each chunk after the first, giving retrieval models cross-boundary context.
     */
    private List<String> applyOverlap(List<String> chunks, int overlapChars) {
        if (overlapChars <= 0 || chunks.size() <= 1) return chunks;
        List<String> result = new ArrayList<>(chunks.size());
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String tail = prev.length() <= overlapChars
                    ? prev
                    : prev.substring(prev.length() - overlapChars);
            result.add(tail + OVERLAP_SEPARATOR + chunks.get(i));
        }
        return result;
    }

    /**
     * Split a single long block of text into sub-chunks of at most {@code maxChars}
     * characters, preferring sentence boundary breaks for both Chinese and English.
     * <p>
     * Sentence delimiters tried (in priority order):
     * Chinese: {@code 。}, {@code ！}, {@code ？}; English: {@code .}, {@code !}, {@code ?}.
     */
    private void splitLongText(String text, int maxChars, List<String> out) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                // Try Chinese sentence delimiters first, then English
                int breakAt = -1;
                for (char delim : new char[]{'。', '！', '？', '.', '!', '?'}) {
                    int pos = lastIndexOf(text, delim, end);
                    if (pos > start + maxChars / 2 && pos > breakAt) {
                        breakAt = pos;
                    }
                }
                if (breakAt > start) {
                    end = breakAt + 1;
                } else {
                    // Fall back to last whitespace
                    int wsAt = lastWhitespace(text, end);
                    if (wsAt > start + maxChars / 2) {
                        end = wsAt + 1;
                    }
                }
            }
            String chunk = text.substring(start, end).strip();
            if (!chunk.isEmpty()) out.add(chunk);
            start = end;
        }
    }

    private int lastIndexOf(String text, char ch, int fromIndex) {
        for (int i = Math.min(fromIndex, text.length() - 1); i >= 0; i--) {
            if (text.charAt(i) == ch) return i;
        }
        return -1;
    }

    private int lastWhitespace(String text, int fromIndex) {
        for (int i = Math.min(fromIndex, text.length() - 1); i >= 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i;
        }
        return -1;
    }
}
