package com.example.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel chatModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final String docsDir;
    private final int chunkMaxChars;
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
            ChatLanguageModel chatModel,
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("${rag.docs.dir:}") String docsDir,
            @Value("${rag.chunk.maxChars:500}") int chunkMaxChars,
            @Value("${rag.topK:3}") int topK,
            @Value("${rag.minScore:0.0}") double minScore,
            @Value("${rag.rerank.enabled:false}") boolean rerankEnabled,
            @Value("${rag.rerank.topN:2}") int rerankTopN,
            @Value("${llm.provider:dashscope}") String llmProvider,
            @Value("${embedding.provider:dashscope}") String embeddingProvider,
            @Value("${vector.store:inmemory}") String vectorStore,
            @Value("${rag.index.maxChunksPerFile}") int maxChunksPerFile,
            @Value("${rag.index.batchSize}") int batchSize,
            @Value("${rag.index.skipHugeFiles.enabled}") boolean skipHugeFiles

    ) {
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.embeddingStore = embeddingStore;
        this.docsDir = docsDir;
        this.chunkMaxChars = chunkMaxChars;
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
                                        "chunkStrategy", "paragraph"
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

        // Embed all chunks
        log.info("rag.reindex embedding start chunks={}", allChunks.size());
//        Response<List<Embedding>> embResponse = embeddingModel.embedAll(allChunks);


        //batchSize从配置文件获取
        int batchSize = this.batchSize;
        List<Embedding> embeddings = new ArrayList<>();
        for (int start = 0; start < allChunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, allChunks.size());
            List<TextSegment> batch = allChunks.subList(start, end);
            Response<List<Embedding>> resp = embeddingModel.embedAll(batch);
            log.info("rag.reindex embedding done chunks={} embeddings={}", allChunks.size(), resp.content().size());

            embeddings.addAll(resp.content());
            embeddingStore.addAll(resp.content(), batch); // 或 chromaStore.addAll
            log.info("rag.reindex embedded {} / {}", end, allChunks.size());
        }

//        log.info("rag.reindex embedding done chunks={} embeddings={}", allChunks.size(), embResponse.content().size());
        log.info("rag.reindex storing embeddings chunks={} embeddings={}", allChunks.size(), embeddings.size());
//        List<Embedding> embeddings = embResponse.content();
//        embeddingStore.addAll(embeddings, allChunks);

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
     *
     * @param question   the query
     * @param maxResults override for topK (null = use configured topK)
     */
    public List<Map<String, Object>> retrieveWithScores(String question, Integer maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(TextSegment.from(question)).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults != null ? maxResults : topK)
                .minScore(minScore)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();
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

    /**
     * Retrieve relevant chunks and answer the question using the chat model.
     * Optionally applies LLM-based reranking when rag.rerank.enabled=true.
     */
    public String ask(String question) {
        List<Map<String, Object>> results = retrieveWithScores(question);
        if (results.isEmpty()) {
            return chatModel.generate(question);
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

        return chatModel.generate(prompt);
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
            String response = chatModel.generate(sb.toString());
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
     * Split text into chunks of at most maxChars characters, trying to split on paragraph/sentence
     * boundaries where possible.
     */
    private List<String> chunkText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\n+");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;

            if (current.length() > 0 && current.length() + trimmed.length() + 2 > chunkMaxChars) {
                chunks.add(current.toString().strip());
                current = new StringBuilder();
            }

            if (trimmed.length() > chunkMaxChars) {
                if (current.length() > 0) {
                    if (current == null ) {
                        log.warn("Skipping empty chunk while processing text: '{}'", current);
                        continue;
                    }
                    chunks.add(current.toString().strip());
                    current = new StringBuilder();
                }
                splitLongText(trimmed, chunkMaxChars, chunks);
            } else {
                if (current.length() > 0) current.append("\n\n");
                current.append(trimmed);
            }
        }

        if (current.length() > 0) {
            chunks.add(current.toString().strip());
        }

        return chunks;
    }

    private void splitLongText(String text, int maxChars, List<String> out) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int breakAt = text.lastIndexOf('.', end);
                if (breakAt > start + maxChars / 2) {
                    end = breakAt + 1;
                }
            }
            out.add(text.substring(start, end).strip());
            start = end;
        }
    }
}
