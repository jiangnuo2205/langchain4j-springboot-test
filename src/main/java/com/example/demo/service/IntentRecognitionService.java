package com.example.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 获客智能体 — 意图识别模块
 * <p>
 * 接受自然语言描述（如"我朋友做钓鱼竿想卖去美国"），
 * 解析出结构化的获客需求：产品类型、目标市场、客户画像、推荐策略。
 * <p>
 * 模拟高级顾问思维，理解表层诉求背后的深层目标。
 */

@Service
public class IntentRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognitionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel chatModel;

    public IntentRecognitionService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public record IntentResult(
            Map<String, Object> parsed,  // 结构化的解析结果
            String rawResponse,          // LLM 原始响应（兜底展示）
            long costMs
    ) {}

    /**
     * P（1）：解析用户的获客意图。
     * todo：Helen 待优化点：缺少外部资料支持（具体有效线索）、而非LLM直接回答
     * todo:Helen 缺少：意图识别：模拟高级顾问思维，理解用户表层诉求背后的深层目标（如资金安全、风险承受能力），避免机械响应。
     * todo：缺少：兜底
     * @param userInput 用户的自然语言描述，支持白话文
     */
    public IntentResult recognizeIntent(String userInput) {
        String prompt = buildPrompt(userInput);

        long start = System.nanoTime();
        String response = chatModel.chat(prompt);
        long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        log.info("trade.intent costMs={} inputLen={} outputLen={}", costMs, userInput.length(), response.length());

        Map<String, Object> parsed = parseResponse(response);
        return new IntentResult(parsed, response, costMs);
    }

    private String buildPrompt(String userInput) {
        return """
                你是一位资深外贸获客顾问。用户会用自然语言（可能是口语化的、模糊的）描述他们的获客需求。
                你需要像一个高级顾问那样，理解用户表层诉求背后的深层目标，并给出专业的结构化分析。
                
                ## 你的任务
                从用户描述中提取并推理以下信息。如果用户没有明确说明某项，请基于行业经验进行合理推断并标注"（推断）"。
                
                ## 输出要求（严格按 JSON 格式，不要输出其他内容）
                ```json
                {
                  "productType": "用户想卖的产品类型",
                  "targetMarkets": ["目标市场列表"],
                  "targetCustomerProfile": {
                    "industryType": "目标客户行业",
                    "companyScale": "目标客户规模",
                    "buyerRole": "采购决策者角色描述",
                    "purchaseFrequency": "采购频率推断"
                  },
                  "suggestedChannels": ["建议的获客渠道，如 LinkedIn / 展会 / B2B平台 / Google Ads"],
                  "keySellingPoints": ["基于产品类型推荐的核心卖点"],
                  "riskWarnings": ["潜在风险提醒"],
                  "recommendedApproach": "建议的切入策略概述",
                  "confidenceLevel": "HIGH / MEDIUM / LOW（基于信息完整度）"
                }
                ```
                
                ## 用户输入
                %s
                """.formatted(userInput);
    }

    private Map<String, Object> parseResponse(String response) {
        try {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String json = response.substring(start, end + 1);
                return MAPPER.readValue(json, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("trade.intent json parse failed err={}", e.getMessage());
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("rawAnalysis", response);
        fallback.put("confidenceLevel", "LOW");
        return fallback;
    }
}
