package com.example.demo.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Short-query regression runner.
 *
 * <p>Calls {@code /api/rag/search} for each short query in the fixed list and prints
 * the top-10 results plus rewrite diagnostics to stdout. Useful for before/after
 * comparison when tuning retrieval parameters.
 *
 * <h2>Run</h2>
 * <pre>{@code
 * mvn compile exec:java -Dexec.mainClass=com.example.demo.eval.ShortQueryRegressionRunner
 * }</pre>
 *
 * <p>Or with custom base URL and k:
 * <pre>{@code
 * mvn compile exec:java \
 *   -Dexec.mainClass=com.example.demo.eval.ShortQueryRegressionRunner \
 *   -Dexec.args="http://localhost:8090 10"
 * }</pre>
 *
 * <p>The server must be running before executing this class.
 */
public class ShortQueryRegressionRunner {

    /** Short queries to test. Edit this list to add or remove cases. */
    private static final List<String> QUERIES = List.of(
            "假期",
            "薪酬",
            "资产化",
            "绩效",
            "领导力"
    );

    private static final String SEP =
            "════════════════════════════════════════════════════════════════";

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8090";
        int k = args.length > 1 ? Integer.parseInt(args[1]) : 10;

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        ObjectMapper mapper = new ObjectMapper();

        System.out.println("Short-Query Regression Runner");
        System.out.printf("BASE_URL : %s%n", baseUrl);
        System.out.printf("K        : %d%n", k);
        System.out.printf("Queries  : %d%n", QUERIES.size());
        System.out.println();

        for (String q : QUERIES) {
            System.out.println(SEP);
            System.out.printf("Query: [%s]%n%n", q);

            String encoded = URLEncoder.encode(q, StandardCharsets.UTF_8);
            String url = baseUrl + "/api/rag/search?q=" + encoded + "&k=" + k;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            if (resp.statusCode() != 200) {
                System.out.printf("[ERROR] HTTP %d: %s%n%n", resp.statusCode(),
                        body.length() > 200 ? body.substring(0, 200) : body);
                continue;
            }

            JsonNode root;
            try {
                root = mapper.readTree(body);
            } catch (Exception e) {
                System.out.printf("[ERROR] Non-JSON response: %s%n%n",
                        body.length() > 200 ? body.substring(0, 200) : body);
                continue;
            }

            // Print rewrite diagnostics
            JsonNode diag = root.path("rewriteDiagnostics");
            if (!diag.isMissingNode() && !diag.isNull()) {
                System.out.println("Rewrite diagnostics:");
                System.out.printf("  enabled       : %s%n", diag.path("rewriteEnabled").asText("-"));
                System.out.printf("  ruleRan       : %s%n", diag.path("ruleExpansionRan").asText("-"));
                System.out.printf("  llmRan        : %s%n", diag.path("llmExpansionRan").asText("-"));
                System.out.printf("  triggerReason : %s%n", diag.path("triggerReason").asText("-"));
                JsonNode variants = diag.path("variantQueries");
                if (variants.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode v : variants) {
                        if (!sb.isEmpty()) sb.append(", ");
                        sb.append(v.asText());
                    }
                    System.out.printf("  variants      : %s%n", sb);
                }
            } else {
                System.out.println("  (no rewriteDiagnostics field — rewriting disabled or older server)");
            }
            System.out.println();

            // Print results
            JsonNode results = root.path("results");
            System.out.printf("Results: %d%n%n", results.size());

            for (int i = 0; i < results.size(); i++) {
                JsonNode r = results.get(i);
                double score = r.path("score").asDouble();
                String sourceId = r.path("sourceId").asText("-");
                String preview = r.path("textPreview").asText("-")
                        .replaceAll("\\s+", " ");
                if (preview.length() > 120) {
                    preview = preview.substring(0, 120) + "…";
                }

                String matchedVariants = "";
                JsonNode mv = r.path("matchedVariants");
                if (mv.isArray() && !mv.isEmpty()) {
                    StringBuilder sb = new StringBuilder(" [matched:");
                    for (JsonNode v : mv) {
                        sb.append(" ").append(v.asText());
                    }
                    sb.append("]");
                    matchedVariants = sb.toString();
                }

                System.out.printf("  %2d. score=%.6f  %s%s%n", i + 1, score, sourceId, matchedVariants);
                System.out.printf("      %s%n", preview);
            }
            System.out.println();
        }

        System.out.println(SEP);
        System.out.println("Done.");
    }
}
