package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * BM25 index service backed by Elasticsearch/OpenSearch.
 * <p>
 * Activated when {@code rag.bm25.enabled=true}.  The service is intentionally
 * stateless (no in-memory cache): every call goes directly to the running ES
 * instance so that index changes are immediately visible to all replicas.
 * <p>
 * Index schema per document:
 * <pre>
 *   chunkId    – stable ID, format: docId#chunk=N
 *   docId      – relative path of the source file
 *   chunkIndex – zero-based chunk number within the file
 *   text       – full chunk text (used for BM25 matching)
 *   sourcePath – absolute path of the source file
 * </pre>
 */
@Service
@ConditionalOnProperty(name = "rag.bm25.enabled", havingValue = "true")
public class Bm25IndexService {

    private static final Logger log = LoggerFactory.getLogger(Bm25IndexService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Hard cap on BM25 result size – protects M1 CPU from excessive ranking work. */
    private static final int ABSOLUTE_MAX_RESULTS = 50;

    private final RestTemplate restTemplate;
    private final String esUrl;
    private final String indexName;
    private final int candidateK;

    public Bm25IndexService(
            @Value("${rag.bm25.elasticsearch.url:http://localhost:9200}") String esUrl,
            @Value("${rag.bm25.indexName:rag-chunks}") String indexName,
            @Value("${rag.hybrid.candidateK:50}") int candidateK
    ) {
        this.esUrl = esUrl.replaceAll("/$", "");
        this.indexName = indexName;
        this.candidateK = Math.min(candidateK, ABSOLUTE_MAX_RESULTS);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.restTemplate = new RestTemplate(factory);

        log.info("bm25.init esUrl={} indexName={} candidateK={}", esUrl, indexName, this.candidateK);
    }

    // ── Record returned by search ──────────────────────────────────────────────

    public record BM25Hit(
            String chunkId,
            String docId,
            int chunkIndex,
            String text,
            String sourcePath,
            double score
    ) {}

    // ── Index management ───────────────────────────────────────────────────────

    /**
     * Delete and recreate the BM25 index with a fresh mapping.
     * Called at the start of every reindex operation.
     */
    public void clearAndCreateIndex() {
        String url = esUrl + "/" + indexName;

        // Delete if exists
        try {
            restTemplate.delete(url);
            log.info("bm25.clearIndex deleted index={}", indexName);
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("bm25.clearIndex index not found (first run), skipping delete index={}", indexName);
        } catch (Exception e) {
            log.warn("bm25.clearIndex failed to delete index={} err={}", indexName, e.getMessage());
        }

        // Create with mapping
        String mapping = """
                {
                  "settings": {
                    "number_of_shards": 1,
                    "number_of_replicas": 0,
                    "refresh_interval": "1s"
                  },
                  "mappings": {
                    "properties": {
                      "chunkId":    { "type": "keyword" },
                      "docId":      { "type": "keyword" },
                      "chunkIndex": { "type": "integer" },
                      "text":       { "type": "text",    "analyzer": "standard" },
                      "sourcePath": { "type": "keyword" }
                    }
                  }
                }
                """;
        try {
            HttpHeaders headers = jsonHeaders();
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.PUT, new HttpEntity<>(mapping, headers), String.class);
            log.info("bm25.createIndex index={} status={}", indexName, resp.getStatusCode());
        } catch (Exception e) {
            log.error("bm25.createIndex failed index={} err={}", indexName, e.getMessage());
            throw new IllegalStateException("Failed to create BM25 index: " + e.getMessage(), e);
        }
    }

    /**
     * Bulk-index the provided chunks into Elasticsearch.
     *
     * @param chunks  segments to index (must be the same list passed to Chroma)
     * @param chunkIds stable IDs in the same order as {@code chunks}
     */
    public void bulkIndex(List<TextSegment> chunks, List<String> chunkIds) {
        if (chunks.isEmpty()) return;

        int batchSize = 200;
        int total = 0;
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            List<TextSegment> batch = chunks.subList(start, end);
            List<String> ids = chunkIds.subList(start, end);

            StringBuilder ndjson = new StringBuilder();
            for (int i = 0; i < batch.size(); i++) {
                TextSegment seg = batch.get(i);
                String chunkId = ids.get(i);
                String docId = seg.metadata() != null ? seg.metadata().getString("docId") : null;
                String sourcePath = seg.metadata() != null ? seg.metadata().getString("sourcePath") : null;
                String chunkIndexStr = seg.metadata() != null ? seg.metadata().getString("chunkIndex") : "0";
                int chunkIndex = 0;
                try { chunkIndex = Integer.parseInt(chunkIndexStr); } catch (NumberFormatException ignored) {}

                try {
                    // Action line
                    ndjson.append(MAPPER.writeValueAsString(
                            Map.of("index", Map.of("_index", indexName, "_id", chunkId))
                    )).append("\n");
                    // Source line
                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put("chunkId", chunkId);
                    if (docId != null) doc.put("docId", docId);
                    doc.put("chunkIndex", chunkIndex);
                    doc.put("text", seg.text() != null ? seg.text() : "");
                    if (sourcePath != null) doc.put("sourcePath", sourcePath);
                    ndjson.append(MAPPER.writeValueAsString(doc)).append("\n");
                } catch (Exception e) {
                    log.warn("bm25.bulkIndex serialization error chunkId={} err={}", chunkId, e.getMessage());
                }
            }

            try {
                HttpHeaders headers = jsonHeaders();
                ResponseEntity<String> resp = restTemplate.exchange(
                        esUrl + "/_bulk",
                        HttpMethod.POST,
                        new HttpEntity<>(ndjson.toString(), headers),
                        String.class
                );
                total += batch.size();
                log.debug("bm25.bulkIndex batch start={} end={} status={}", start, end, resp.getStatusCode());
            } catch (Exception e) {
                log.error("bm25.bulkIndex failed start={} end={} err={}", start, end, e.getMessage());
                throw new IllegalStateException("BM25 bulk index failed: " + e.getMessage(), e);
            }
        }
        log.info("bm25.bulkIndex complete total={} index={}", total, indexName);
    }

    /**
     * Full-text BM25 search.
     *
     * @param query   the query text
     * @param topN    max results; capped at {@value #ABSOLUTE_MAX_RESULTS}
     * @return ranked list of BM25 hits (highest score first)
     */
    public List<BM25Hit> search(String query, int topN) {
        int size = Math.min(topN, ABSOLUTE_MAX_RESULTS);
        String body;
        try {
            body = MAPPER.writeValueAsString(Map.of(
                    "query", Map.of(
                            "match", Map.of(
                                    "text", Map.of(
                                            "query", query,
                                            "operator", "or"
                                    )
                            )
                    ),
                    "_source", List.of("chunkId", "docId", "chunkIndex", "text", "sourcePath"),
                    "size", size
            ));
        } catch (Exception e) {
            log.error("bm25.search serialization failed err={}", e.getMessage());
            return List.of();
        }

        try {
            HttpHeaders headers = jsonHeaders();
            ResponseEntity<String> resp = restTemplate.exchange(
                    esUrl + "/" + indexName + "/_search",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            return parseSearchResponse(resp.getBody());
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("bm25.search index not found index={} – returning empty", indexName);
            return List.of();
        } catch (ResourceAccessException e) {
            log.warn("bm25.search ES unreachable err={} – returning empty", e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.error("bm25.search failed err={}", e.getMessage());
            return List.of();
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private List<BM25Hit> parseSearchResponse(String responseBody) {
        if (responseBody == null) return List.of();
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode hits = root.path("hits").path("hits");
            List<BM25Hit> results = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode src = hit.path("_source");
                String chunkId = src.path("chunkId").asText(hit.path("_id").asText(""));
                String docId = src.path("docId").asText(null);
                int chunkIndex = src.path("chunkIndex").asInt(0);
                String text = src.path("text").asText("");
                String sourcePath = src.path("sourcePath").asText(null);
                double score = hit.path("_score").asDouble(0.0);
                results.add(new BM25Hit(chunkId, docId, chunkIndex, text, sourcePath, score));
            }
            return results;
        } catch (Exception e) {
            log.error("bm25.parseResponse failed err={}", e.getMessage());
            return List.of();
        }
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
