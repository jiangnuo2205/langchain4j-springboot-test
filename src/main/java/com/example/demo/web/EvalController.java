package com.example.demo.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.List;
import java.util.ArrayList;
import com.example.demo.service.RagService;
import com.example.demo.model.EvalCase;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    @Autowired
    private RagService ragService;

    @PostMapping("/run")
    public String runEval(@RequestParam int k) {
        List<EvalCase> evalCases = readEvalCases("eval/cases.jsonl");
        int total = evalCases.size();
        int hit = 0;
        List<String> missIds = new ArrayList<>();
        long start = System.currentTimeMillis();

        for (EvalCase evalCase : evalCases) {
            // Perform retrieval
            String query = evalCase.getQuery();
            var results = ragService.retrieveWithScores(query, k);
            boolean foundHit = false;

            for (var result : results) {
                if (result.getMetadata().getDocId().equals(evalCase.getGoldDocId())) {
                    foundHit = true;
                    hit++;
                    break;
                }
            }

            if (!foundHit) {
                missIds.add(evalCase.getId());
            }
        }

        long avgMs = (System.currentTimeMillis() - start) / total;
        writeResultsCSV(hit, total, avgMs, missIds);

        return String.format("{\"k\": %d, \"total\": %d, \"hit\": %d, \"recall\": %.2f, \"avgMs\": %d, \"missIds\": %s, \"outputPath\": \"eval/results.csv\"}", k, total, hit, (double) hit / total, avgMs, missIds);
    }

    private List<EvalCase> readEvalCases(String filePath) {
        List<EvalCase> evalCases = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                evalCases.add(new Gson().fromJson(line, EvalCase.class));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return evalCases;
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
