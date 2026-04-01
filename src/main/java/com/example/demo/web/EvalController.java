package com.example.demo.web;

import com.example.demo.service.RagService;
<<<<<<< HEAD
import com.fasterxml.jackson.databind.ObjectMapper;
=======
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
>>>>>>> c3ade46b055b23ac1cf8d788f5dcf94b84a94ef5
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
<<<<<<< HEAD
import java.nio.charset.StandardCharsets;
import java.util.*;
=======
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
>>>>>>> c3ade46b055b23ac1cf8d788f5dcf94b84a94ef5

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

<<<<<<< HEAD
    private final RagService ragService;

    public EvalController(RagService ragService) {
        this.ragService = ragService;
    }

    // 简单的 case 结构：每行一个 JSON
    public record EvalCase(
            String id,
            String query,
            String goldDocId,
            Boolean shouldRefuse
    ) {}

    @PostMapping("/run")
    public Map<String, Object> runEval(@RequestParam(name = "k", defaultValue = "5") int k) throws Exception {
        List<EvalCase> cases = readCases(new File("eval/cases.jsonl"));
        if (cases.isEmpty()) {
            return Map.of(
                    "k", k,
                    "total", 0,
                    "hit", 0,
                    "recall", 0.0,
                    "avgMs", 0,
                    "outputPath", "eval/results.csv",
                    "message", "No cases found in eval/cases.jsonl"
            );
        }

=======
    @Autowired
    private RagService ragService;

    @PostMapping("/run")
    public String runEval(@RequestParam int k) {
        List<Map<String, Object>> evalCases = readEvalCases("eval/cases.jsonl");
        int total = evalCases.size();
        if (total == 0) {
            return "{\"error\": \"No eval cases found in eval/cases.jsonl\"}";
        }
>>>>>>> c3ade46b055b23ac1cf8d788f5dcf94b84a94ef5
        int hit = 0;
        long totalMs = 0L;
        List<String> missIds = new ArrayList<>();

<<<<<<< HEAD
        File out = new File("eval/results.csv");
        out.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(out, StandardCharsets.UTF_8)) {
            writer.append("id,hit,goldDocId,topDocIds,latencyMs,query\n");

            for (EvalCase c : cases) {
                long t0 = System.currentTimeMillis();
                List<Map<String, Object>> results = ragService.retrieveWithScores(c.query(), k);
                long latency = System.currentTimeMillis() - t0;

                totalMs += latency;

                List<String> topDocIds = new ArrayList<>();
                boolean found = false;

                for (Map<String, Object> r : results) {
                    Object metaObj = r.get("metadata");
                    String docId = null;

                    if (metaObj instanceof Map<?, ?> metaMap) {
                        Object d = metaMap.get("docId");
                        if (d != null) docId = String.valueOf(d);
                    }

                    if (docId != null) {
                        topDocIds.add(docId);
                        if (c.goldDocId() != null && !c.goldDocId().isBlank() && docId.equals(c.goldDocId())) {
                            found = true;
                        }
                    }
=======
        for (Map<String, Object> evalCase : evalCases) {
            String id = (String) evalCase.getOrDefault("id", "?");
            String question = (String) evalCase.getOrDefault("question", "");
            @SuppressWarnings("unchecked")
            List<String> relevant = (List<String>) evalCase.getOrDefault("relevant", List.of());

            List<Map<String, Object>> results = ragService.retrieveWithScores(question, k);
            boolean foundHit = false;

            for (Map<String, Object> result : results) {
                String sourceId = (String) result.get("sourceId");
                if (sourceId != null && relevant.stream().anyMatch(sourceId::contains)) {
                    foundHit = true;
                    break;
>>>>>>> c3ade46b055b23ac1cf8d788f5dcf94b84a94ef5
                }

<<<<<<< HEAD
                if (found) {
                    hit++;
                } else {
                    missIds.add(c.id());
                }

                writer.append(escapeCsv(c.id())).append(",");
                writer.append(found ? "1" : "0").append(",");
                writer.append(escapeCsv(nullToEmpty(c.goldDocId()))).append(",");
                writer.append(escapeCsv(String.join("|", topDocIds))).append(",");
                writer.append(String.valueOf(latency)).append(",");
                writer.append(escapeCsv(c.query())).append("\n");
=======
            if (foundHit) {
                hit++;
            } else {
                missIds.add(id);
>>>>>>> c3ade46b055b23ac1cf8d788f5dcf94b84a94ef5
            }
        }

        double recall = (cases.size() == 0) ? 0.0 : (double) hit / (double) cases.size();
        long avgMs = totalMs / cases.size();

<<<<<<< HEAD
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("k", k);
        resp.put("total", cases.size());
        resp.put("hit", hit);
        resp.put("recall", recall);
        resp.put("avgMs", avgMs);
        resp.put("missIds", missIds);
        resp.put("outputPath", "eval/results.csv");
        return resp;
    }

    private static List<EvalCase> readCases(File f) throws Exception {
        if (!f.exists()) return List.of();

        List<EvalCase> cases = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                cases.add(MAPPER.readValue(s, EvalCase.class));
=======
        return String.format(
                "{\"k\": %d, \"total\": %d, \"hit\": %d, \"recall\": %.2f, \"avgMs\": %d, \"missIds\": %s, \"outputPath\": \"eval/results.csv\"}",
                k, total, hit, (double) hit / total, avgMs, missIds);
    }

    private List<Map<String, Object>> readEvalCases(String filePath) {
        List<Map<String, Object>> cases = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                cases.add(MAPPER.readValue(line, new TypeReference<>() {}));
>>>>>>> c3ade46b055b23ac1cf8d788f5dcf94b84a94ef5
            }
        }
        return cases;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String escapeCsv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }
}