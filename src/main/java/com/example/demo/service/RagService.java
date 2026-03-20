package com.example.demo.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel chatModel;
    private final String docsDir;
    private final int chunkMaxChars;
    private final int topK;

    private volatile InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

    // 新增：用于 /api/rag/stats 的“索引元数据快照”
    public record IndexedChunkMeta(
            String id,
            String text,
            int vectorDim,
            long estimatedVectorBytes,
            long estimatedTextBytesUtf8
    ) {}

    private volatile List<IndexedChunkMeta> indexedMetas = List.of();

    public RagService(
            EmbeddingModel embeddingModel,
            ChatLanguageModel chatModel,
            @Value("${rag.docs.dir:}") String docsDir,
            @Value("${rag.chunk.maxChars:500}") int chunkMaxChars,
            @Value("${rag.topK:3}") int topK) {
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.docsDir = docsDir;
        this.chunkMaxChars = chunkMaxChars;
        this.topK = topK;
    }

    /** 新增：stats */
    public Map<String, Object> stats(int topN) {
        int n = Math.max(0, Math.min(topN, 200));
        List<IndexedChunkMeta> metas = this.indexedMetas;

        int chunks = metas.size();
        int vectorDimMax = 0;
        long vectorBytes = 0L;
        long textBytes = 0L;

        for (IndexedChunkMeta m : metas) {
            vectorDimMax = Math.max(vectorDimMax, m.vectorDim());
            vectorBytes += m.estimatedVectorBytes();
            textBytes += m.estimatedTextBytesUtf8();
        }

        List<String> firstIds = metas.stream().limit(n).map(IndexedChunkMeta::id).toList();

        return Map.of(
                "chunks", chunks,
                "vectorDimMax", vectorDimMax,
                "estimatedVectorBytes", vectorBytes,
                "estimatedTextBytesUtf8", textBytes,
                "firstIds", firstIds
        );
    }

    /**
     * Rebuild the index: scan docsDir for .txt and .md files, chunk content, embed and store.
     * @return number of chunks indexed
     */
    public int reindex() {
        if (docsDir == null || docsDir.isBlank()) {
            log.warn("rag.docs.dir is not configured – nothing to index");
            store = new InMemoryEmbeddingStore<>();
            indexedMetas = List.of();
            return 0;
        }

        Path dir = Paths.get(docsDir);
        if (!Files.isDirectory(dir)) {
            log.warn("rag.docs.dir={} is not a directory", docsDir);
            store = new InMemoryEmbeddingStore<>();
            indexedMetas = List.of();
            return 0;
        }

        // 改动：我们同时维护 chunk 的 id
        List<TextSegment> allChunks = new ArrayList<>();
        List<String> allChunkIds = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".txt") || name.endsWith(".md");
                    })
                    .forEach(file -> {
                        try {
                            String content = Files.readString(file);
                            List<String> chunks = chunkText(content);
                            String fileName = dir.relativize(file).toString();

                            for (int i = 0; i < chunks.size(); i++) {
                                String chunk = chunks.get(i);
                                allChunks.add(TextSegment.from(chunk));
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
            store = new InMemoryEmbeddingStore<>();
            indexedMetas = List.of();
            return 0;
        }

        // Embed all chunks
        Response<List<Embedding>> embResponse = embeddingModel.embedAll(allChunks);
        List<Embedding> embeddings = embResponse.content();

        InMemoryEmbeddingStore<TextSegment> newStore = new InMemoryEmbeddingStore<>();
        newStore.addAll(embeddings, allChunks);
        this.store = newStore;

        // 新增：构建元数据快照（用于 stats）
        List<IndexedChunkMeta> metas = new ArrayList<>(allChunks.size());
        for (int i = 0; i < allChunks.size(); i++) {
            String id = allChunkIds.get(i);
            String text = allChunks.get(i).text();

            Embedding emb = (embeddings != null && i < embeddings.size()) ? embeddings.get(i) : null;
            int dim = (emb == null || emb.vector() == null) ? 0 : emb.vector().length;

            long vecBytes = (long) dim * 4L; // float 数据本体估算
            long txtBytes = (text == null) ? 0L : text.getBytes(StandardCharsets.UTF_8).length;

            metas.add(new IndexedChunkMeta(id, text, dim, vecBytes, txtBytes));
        }
        this.indexedMetas = List.copyOf(metas);

        log.info("rag.reindex done dir={} chunks={}", docsDir, allChunks.size());
        return allChunks.size();
    }

    /**
     * Retrieve topK chunks most similar to the question, build context prompt, and answer.
     */
    public List<String> retrieve(String question) {
        Embedding queryEmbedding = embeddingModel.embed(TextSegment.from(question)).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(0.0)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = store.search(request).matches();
        List<String> chunks = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            if (match.embedded() != null) {
                chunks.add(match.embedded().text());
            }
        }
        return chunks;
    }

    /**
     * Retrieve relevant chunks and answer the question using the chat model.
     */
    public String ask(String question) {
        List<String> chunks = retrieve(question);
        if (chunks.isEmpty()) {
            return chatModel.generate(question);
        }

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            context.append("[").append(i + 1).append("] ").append(chunks.get(i)).append("\n\n");
        }

        String prompt = "Use the following context to answer the question.\n\n"
                + "Context:\n" + context
                + "Question: " + question;

        return chatModel.generate(prompt);
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
        // Split on double newlines (paragraphs) first
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
                // Paragraph itself is too long – split by sentences or hard-cut
                if (current.length() > 0) {
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
            // Try to break at the last period or newline before end
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
