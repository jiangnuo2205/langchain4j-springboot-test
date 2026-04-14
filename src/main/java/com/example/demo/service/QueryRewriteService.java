package com.example.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Query rewriting service implementing Strategy B:
 * <ol>
 *   <li>Always run cheap rule-based expansion first for short queries.</li>
 *   <li>Trigger LLM multi-query only when query is short AND retrieval confidence is low.</li>
 * </ol>
 *
 * <p>Default LLM provider: Ollama. Fallback: DashScope.
 *
 * <p>Controlled by {@code rag.queryRewrite.enabled} (default {@code false}).
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Suffix allowlist for rule-based expansion. */
    static final List<String> SUFFIX_ALLOWLIST = List.of(
            "能力", "平台", "流程", "体系", "策略", "标准", "落地",
            "方法", "管理", "评估", "制度", "规范"
    );

    private final boolean enabled;
    private final int shortQueryMinLen;
    private final int shortQueryMaxLen;
    private final boolean ruleExpansionEnabled;
    private final int ruleExpansionMaxVariants;
    private final boolean llmExpansionEnabled;
    private final int llmExpansionMaxVariants;
    private final double llmTriggerMinTopScore;
    private final String provider;
    private final String fallbackProvider;

    // Config for lazily building LLM models
    private final String ollamaBaseUrl;
    private final String ollamaChatModelName;
    private final long ollamaTimeout;
    private final String dashscopeApiKey;
    private final String dashscopeModel;

    // Lazily initialised LLM models (thread-safe via volatile + synchronized)
    private volatile ChatModel primaryChatModel;
    private volatile ChatModel fallbackChatModel;
    private volatile boolean primaryFailed = false;

    public QueryRewriteService(
            @Value("${rag.queryRewrite.enabled:false}") boolean enabled,
            @Value("${rag.queryRewrite.shortQuery.minLen:2}") int shortQueryMinLen,
            @Value("${rag.queryRewrite.shortQuery.maxLen:6}") int shortQueryMaxLen,
            @Value("${rag.queryRewrite.ruleExpansion.enabled:true}") boolean ruleExpansionEnabled,
            @Value("${rag.queryRewrite.ruleExpansion.maxVariants:3}") int ruleExpansionMaxVariants,
            @Value("${rag.queryRewrite.llmExpansion.enabled:true}") boolean llmExpansionEnabled,
            @Value("${rag.queryRewrite.llmExpansion.maxVariants:3}") int llmExpansionMaxVariants,
            @Value("${rag.queryRewrite.llmTrigger.minTopScore:0.02}") double llmTriggerMinTopScore,
            @Value("${rag.queryRewrite.provider:ollama}") String provider,
            @Value("${rag.queryRewrite.fallbackProvider:dashscope}") String fallbackProvider,
            @Value("${ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${ollama.chat-model:qwen3:4b}") String ollamaChatModelName,
            @Value("${ollama.timeout:60}") long ollamaTimeout,
            @Value("${dashscope.api-key:}") String dashscopeApiKey,
            @Value("${dashscope.model:qwen-turbo}") String dashscopeModel
    ) {
        this.enabled = enabled;
        this.shortQueryMinLen = shortQueryMinLen;
        this.shortQueryMaxLen = shortQueryMaxLen;
        this.ruleExpansionEnabled = ruleExpansionEnabled;
        this.ruleExpansionMaxVariants = Math.max(1, ruleExpansionMaxVariants);
        this.llmExpansionEnabled = llmExpansionEnabled;
        this.llmExpansionMaxVariants = Math.max(1, llmExpansionMaxVariants);
        this.llmTriggerMinTopScore = llmTriggerMinTopScore;
        this.provider = provider;
        this.fallbackProvider = fallbackProvider;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.ollamaChatModelName = ollamaChatModelName;
        this.ollamaTimeout = ollamaTimeout;
        this.dashscopeApiKey = dashscopeApiKey;
        this.dashscopeModel = dashscopeModel;
    }

    /** @return {@code true} when query rewriting is enabled via config. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Result of a rewrite operation.
     *
     * @param variantQueries   all queries to retrieve for (original always first)
     * @param ruleExpansionRan whether rule expansion was applied
     * @param llmExpansionRan  whether LLM multi-query was triggered
     * @param llmProvider      which LLM provider was used ({@code null} if LLM not triggered)
     * @param triggerReason    human-readable reason string
     */
    public record RewriteResult(
            List<String> variantQueries,
            boolean ruleExpansionRan,
            boolean llmExpansionRan,
            String llmProvider,
            String triggerReason
    ) {}

    /** @return {@code true} if the query length falls within the short-query range. */
    public boolean isShortQuery(String query) {
        int len = query.trim().length();
        return len >= shortQueryMinLen && len <= shortQueryMaxLen;
    }

    /**
     * Phase-1 rewrite: rule expansion only (no LLM call, no topScore needed).
     * Always returns at least the original query.
     */
    public RewriteResult ruleOnlyRewrite(String query) {
        List<String> variants = new ArrayList<>();
        variants.add(query);

        if (!enabled) {
            return new RewriteResult(variants, false, false, null, "disabled");
        }

        boolean isShort = isShortQuery(query);
        boolean ruleRan = false;

        if (ruleExpansionEnabled && isShort) {
            List<String> ruleVariants = ruleExpand(query);
            for (String v : ruleVariants) {
                if (!variants.contains(v) && variants.size() <= ruleExpansionMaxVariants + 1) {
                    variants.add(v);
                }
            }
            ruleRan = !ruleVariants.isEmpty();
        }

        String reason = ruleRan ? "rule_expansion"
                : (isShort ? "short_no_rule" : "not_short");
        return new RewriteResult(Collections.unmodifiableList(variants), ruleRan, false, null, reason);
    }

    /**
     * Phase-2 rewrite: conditionally trigger LLM multi-query based on retrieval confidence.
     * Returns additional LLM-generated variant queries (may be empty).
     *
     * @param query    original query
     * @param topScore top fused score from phase-1 retrieval (0 if no results)
     */
    public List<String> maybeLlmRewrite(String query, double topScore) {
        if (!enabled || !llmExpansionEnabled) {
            return List.of();
        }
        if (!isShortQuery(query)) {
            return List.of();
        }
        if (topScore >= llmTriggerMinTopScore) {
            log.debug("rag.queryRewrite LLM not triggered topScore={} >= threshold={}", topScore, llmTriggerMinTopScore);
            return List.of();
        }
        log.info("rag.queryRewrite LLM triggered query='{}' topScore={} threshold={}",
                query, topScore, llmTriggerMinTopScore);
        try {
            return llmRewrite(query);
        } catch (Exception e) {
            log.warn("rag.queryRewrite LLM rewrite failed query='{}' err={}", query, e.getMessage());
            return List.of();
        }
    }

    // ── Rule expansion ─────────────────────────────────────────────────────────

    /**
     * Rule-based expansion: suffix expansion + optional morphological split.
     * Bounded to at most {@code ruleExpansionMaxVariants} variants.
     */
    List<String> ruleExpand(String query) {
        String q = query.trim();
        List<String> variants = new ArrayList<>();

        // Suffix expansion: e.g. "资产化" → "资产化能力", "资产化平台"
        for (String suffix : SUFFIX_ALLOWLIST) {
            if (variants.size() >= ruleExpansionMaxVariants) break;
            variants.add(q + suffix);
        }

        // Morphological split expansion: "资产化" → "资产" (for BM25 friendliness)
        if (q.length() >= 3 && variants.size() < ruleExpansionMaxVariants + 1) {
            char last = q.charAt(q.length() - 1);
            if (last == '化' || last == '性' || last == '力' || last == '感') {
                String stem = q.substring(0, q.length() - 1);
                if (!stem.isBlank() && !variants.contains(stem)) {
                    variants.add(stem);
                }
            }
        }

        return variants;
    }

    // ── LLM multi-query rewrite ────────────────────────────────────────────────

    /**
     * LLM multi-query rewrite: calls the configured rewrite model.
     * Tries the primary provider first; on failure, falls back to the secondary provider.
     */
    List<String> llmRewrite(String query) {
        String prompt = buildRewritePrompt(query);

        // Try primary provider
        if (!primaryFailed) {
            try {
                ChatModel model = getPrimaryModel();
                if (model != null) {
                    String response = model.chat(prompt);
                    List<String> variants = parseVariantsFromResponse(response);
                    if (!variants.isEmpty()) {
                        log.info("rag.queryRewrite llm primary={} query='{}' variants={}",
                                provider, query, variants);
                        return variants;
                    }
                }
            } catch (Exception e) {
                log.warn("rag.queryRewrite primary LLM failed provider={} err={}", provider, e.getMessage());
                primaryFailed = true;
            }
        }

        // Fallback provider
        try {
            ChatModel fallback = getFallbackModel();
            if (fallback != null) {
                String response = fallback.chat(prompt);
                List<String> variants = parseVariantsFromResponse(response);
                if (!variants.isEmpty()) {
                    log.info("rag.queryRewrite llm fallback={} query='{}' variants={}",
                            fallbackProvider, query, variants);
                    return variants;
                }
            }
        } catch (Exception e) {
            log.warn("rag.queryRewrite fallback LLM failed provider={} err={}", fallbackProvider, e.getMessage());
        }

        return List.of();
    }

    private String buildRewritePrompt(String query) {
        return "You are a retrieval query rewriter. Given a short Chinese query, generate "
                + llmExpansionMaxVariants
                + " alternative query variants for document retrieval.\n\n"
                + "Rules:\n"
                + "- Do NOT add new facts or assumptions\n"
                + "- Keep the same language as the input (Chinese)\n"
                + "- Preserve proper nouns and numbers exactly\n"
                + "- Each variant expresses the same information need differently\n"
                + "- Output ONLY a JSON array of strings, e.g. [\"variant1\", \"variant2\"]\n\n"
                + "Query: " + query + "\n\nJSON array:";
    }

    private List<String> parseVariantsFromResponse(String response) {
        try {
            int start = response.indexOf('[');
            int end = response.lastIndexOf(']');
            if (start < 0 || end <= start) return List.of();
            String json = response.substring(start, end + 1);
            List<String> variants = MAPPER.readValue(json, new TypeReference<>() {});
            return variants.stream()
                    .filter(v -> v != null && !v.isBlank())
                    .limit(llmExpansionMaxVariants)
                    .toList();
        } catch (Exception e) {
            log.warn("rag.queryRewrite failed to parse LLM response: {}",
                    response.length() > 200 ? response.substring(0, 200) : response);
            return List.of();
        }
    }

    // ── Provider names (for diagnostics) ─────────────────────────────────────

    public String getPrimaryProviderName() {
        return provider;
    }

    public String getFallbackProviderName() {
        return fallbackProvider;
    }

    // ── Model builders ────────────────────────────────────────────────────────

    private synchronized ChatModel getPrimaryModel() {
        if (primaryChatModel == null) {
            primaryChatModel = buildModel(provider);
        }
        return primaryChatModel;
    }

    private synchronized ChatModel getFallbackModel() {
        if (fallbackChatModel == null) {
            fallbackChatModel = buildModel(fallbackProvider);
        }
        return fallbackChatModel;
    }

    private ChatModel buildModel(String providerName) {
        if ("ollama".equalsIgnoreCase(providerName)) {
            // Use a shorter timeout for rewrite calls to keep p95 latency low
            long rewriteTimeout = Math.min(ollamaTimeout, 30L);
            return OllamaChatModel.builder()
                    .baseUrl(ollamaBaseUrl)
                    .modelName(ollamaChatModelName)
                    .temperature(0.3)
                    .timeout(Duration.ofSeconds(rewriteTimeout))
                    .build();
        }
        if ("dashscope".equalsIgnoreCase(providerName)) {
            if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
                log.warn("rag.queryRewrite dashscope api-key not set; cannot use DashScope for rewrite");
                return null;
            }
            return QwenChatModel.builder()
                    .apiKey(dashscopeApiKey)
                    .modelName(dashscopeModel)
                    .temperature(0.3f)
                    .build();
        }
        log.warn("rag.queryRewrite unknown provider={}", providerName);
        return null;
    }
}
