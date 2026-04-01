package com.example.demo.web;

import com.example.demo.service.RagService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RagService ragService;

    @PostMapping("/run")
    public String runEval(@RequestParam int k) {
        List<Map<String, Object>> evalCases = readEvalCases("eval/cases.jsonl");
        int total = evalCases.size();
        if (total == 0) {
            return "{\"error\": \"No eval cases found in eval/cases.jsonl\"}";
        }
        int hit = 0;
        List<String> missIds = new ArrayList<>();
        long start = System.currentTimeMillis();

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
                }
            }

            if (foundHit) {
                hit++;
            } else {
                missIds.add(id);
            }
        }

        long avgMs = (System.currentTimeMillis() - start) / total;
        writeResultsCSV(hit, total, avgMs, missIds);

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
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return cases;
    }

    private void writeResultsCSV(int hit, int total, long avgMs, List<String> missIds) {
        try (FileWriter writer = new FileWriter("eval/results.csv")) {
            writer.append("hit,total,avgMs,missIds\n");
            writer.append(String.format("%d,%d,%d,%s\n", hit, total, avgMs, String.join("|", missIds)));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
