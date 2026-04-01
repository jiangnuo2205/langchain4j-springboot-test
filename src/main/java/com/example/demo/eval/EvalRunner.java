package com.example.demo.eval;

import com.example.demo.service.RagService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Evaluation harness for RAG retrieval quality.
 *
 * Activated when Spring profile "eval" is active.
 * Run via: mvn -DskipTests exec:java
 *   (exec plugin is configured to pass --spring.profiles.active=eval)
 *
 * Input:  eval/cases.jsonl  – one JSON object per line, with fields:
 *           id         (string)
 *           question   (string)
 *           relevant   (string[]) – list of expected docId substrings
 *           ask        (boolean, optional) – whether to also call ask()
 *
 * Output: eval/results.csv  – one row per case
 *         Console summary table
 */
@Component
@Profile("eval")
public class EvalRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RagService ragService;

    public EvalRunner(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public void run(String... args) throws Exception {
        Path casesPath = Paths.get("eval/cases.jsonl");
        Path resultsPath = Paths.get("eval/results.csv");

        if (!Files.exists(casesPath)) {
            log.error("eval/cases.jsonl not found. Create it first.");
            return;
        }

        List<EvalCase> cases = loadCases(casesPath);
        if (cases.isEmpty()) {
            log.warn("No eval cases found in {}", casesPath);
            return;
        }

        log.info("eval starting cases={}", cases.size());

        // First, reindex to make sure store is populated
        int indexed = ragService.reindex();
        log.info("eval reindex done chunks={}", indexed);

        List<EvalResult> results = new ArrayList<>();
        int recallHit = 0;

        for (EvalCase c : cases) {
            EvalResult r = evalCase(c);
            results.add(r);
            if (r.recallAtK()) recallHit++;
            log.info("eval case id={} recallAtK={} score={}", c.id(), r.recallAtK(), r.topScore());
        }

        double recallRate = cases.isEmpty() ? 0.0 : (double) recallHit / cases.size();

        writeResults(resultsPath, results);
        printSummary(results, recallRate);
    }

    private EvalResult evalCase(EvalCase c) {
        List<Map<String, Object>> retrieved = ragService.retrieveWithScores(c.question());

        boolean recallAtK = false;
        double topScore = retrieved.isEmpty() ? 0.0 : (double) retrieved.get(0).get("score");
        List<String> retrievedIds = new ArrayList<>();

        for (Map<String, Object> r : retrieved) {
            String sourceId = (String) r.get("sourceId");
            if (sourceId != null) retrievedIds.add(sourceId);
            // Check if any expected relevant doc is contained in retrieved sourceId
            if (!recallAtK && c.relevant() != null) {
                for (String rel : c.relevant()) {
                    if (sourceId != null && sourceId.contains(rel)) {
                        recallAtK = true;
                        break;
                    }
                }
            }
        }

        String answer = null;
        if (Boolean.TRUE.equals(c.ask())) {
            try {
                answer = ragService.ask(c.question());
            } catch (Exception e) {
                answer = "ERROR: " + e.getMessage();
            }
        }

        return new EvalResult(c.id(), c.question(), recallAtK, topScore, retrievedIds, answer);
    }

    private List<EvalCase> loadCases(Path path) throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                try {
                    Map<String, Object> obj = MAPPER.readValue(line, new TypeReference<>() {});
                    String id = (String) obj.getOrDefault("id", "?");
                    String question = (String) obj.getOrDefault("question", "");
                    @SuppressWarnings("unchecked")
                    List<String> relevant = (List<String>) obj.getOrDefault("relevant", List.of());
                    Boolean ask = obj.containsKey("ask") ? (Boolean) obj.get("ask") : false;
                    cases.add(new EvalCase(id, question, relevant, ask));
                } catch (Exception e) {
                    log.warn("eval skipping malformed line: {} err={}", line, e.getMessage());
                }
            }
        }
        return cases;
    }

    private void writeResults(Path path, List<EvalResult> results) throws IOException {
        Files.createDirectories(path.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            w.println("id,question,recallAtK,topScore,retrievedIds,answer");
            for (EvalResult r : results) {
                w.printf("%s,\"%s\",%b,%.4f,\"%s\",\"%s\"%n",
                        escapeCsv(r.id()),
                        escapeCsv(r.question()),
                        r.recallAtK(),
                        r.topScore(),
                        escapeCsv(String.join("|", r.retrievedIds())),
                        r.answer() != null ? escapeCsv(r.answer()) : "");
            }
        }
        log.info("eval results written to {}", path);
    }

    private void printSummary(List<EvalResult> results, double recallRate) {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.printf("║  Eval Summary   cases=%-5d  Recall@K=%.2f%%           ║%n",
                results.size(), recallRate * 100);
        System.out.println("╠══════════╦══════════╦══════════╦════════════════════╣");
        System.out.println("║ id       ║ recall@K ║ topScore ║ question           ║");
        System.out.println("╠══════════╬══════════╬══════════╬════════════════════╣");
        for (EvalResult r : results) {
            String q = r.question().length() > 18 ? r.question().substring(0, 15) + "..." : r.question();
            System.out.printf("║ %-8s ║ %-8b ║ %-8.4f ║ %-18s ║%n",
                    truncate(r.id(), 8), r.recallAtK(), r.topScore(), q);
        }
        System.out.println("╚══════════╩══════════╩══════════╩════════════════════╝");
        System.out.printf("%nOverall Recall@K: %.2f%% (%d/%d)%n",
                recallRate * 100, (int) (recallRate * results.size()), results.size());
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String escapeCsv(String s) {
        return s == null ? "" : s.replace("\"", "\"\"");
    }

    // ── Records ────────────────────────────────────────────────────────────────

    public record EvalCase(String id, String question, List<String> relevant, Boolean ask) {}

    public record EvalResult(
            String id,
            String question,
            boolean recallAtK,
            double topScore,
            List<String> retrievedIds,
            String answer) {}
}
