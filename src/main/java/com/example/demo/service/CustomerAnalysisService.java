package com.example.demo.service;

import com.example.demo.entity.CommunicationRecord;
import com.example.demo.entity.Customer;
import com.example.demo.repository.CommunicationRecordRepository;
import com.example.demo.repository.CustomerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 转化智能体 — 核心服务
 * <p>
 * 职责：
 * <ol>
 *   <li>读取客户的历史沟通记录</li>
 *   <li>调用 LLM 生成客户画像（行业、规模、需求意向、合作障碍）</li>
 *   <li>自动打 A/B/C/D 分级标签</li>
 *   <li>生成个性化的跟进话术和切入建议</li>
 * </ol>
 * <p>
 * 复用现有 ChatModel Bean（DashScope / Ollama），与 RagService 使用同一个模型实例。
 */
@Service
public class CustomerAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CustomerAnalysisService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final CustomerRepository customerRepo;
    private final CommunicationRecordRepository recordRepo;

    public CustomerAnalysisService(ChatModel chatModel,
                                   CustomerRepository customerRepo,
                                   CommunicationRecordRepository recordRepo) {
        this.chatModel = chatModel;
        this.customerRepo = customerRepo;
        this.recordRepo = recordRepo;
    }

    // ── 结果 Records ─────────────────────────────────────────────────────────

    public record AnalysisResult(
            String profile,          // AI 生成的客户画像
            String grade,            // A / B / C / D
            String gradeReason,      // 分级理由
            String followUp,         // 跟进建议
            long costMs              // LLM 调用耗时
    ) {}

    // ── 主方法：分析单个客户 ─────────────────────────────────────────────────

    /**
     * 对指定客户执行全链路 AI 分析：画像 → 分级 → 跟进建议
     * 结果会同时写入数据库。
     */
    public AnalysisResult analyzeCustomer(Long customerId) {
        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        List<CommunicationRecord> records = recordRepo.findByCustomerIdOrderByCommunicatedAtDesc(customerId);

        String prompt = buildAnalysisPrompt(customer, records);

        long start = System.nanoTime();
        String llmResponse = chatModel.chat(prompt);
        long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        log.info("trade.analysis customerId={} companyName='{}' costMs={} responseLen={}",
                customerId, customer.getCompanyName(), costMs, llmResponse.length());

        // 解析 LLM 响应
        Map<String, String> parsed = parseLlmResponse(llmResponse);

        String profile = parsed.getOrDefault("profile", llmResponse);
        String grade = parsed.getOrDefault("grade", "C");
        String gradeReason = parsed.getOrDefault("gradeReason", "");
        String followUp = parsed.getOrDefault("followUp", "");

        // 持久化到数据库
        customer.setAiProfile(profile);
        customer.setGrade(grade.toUpperCase().trim());
        customer.setGradeReason(gradeReason);
        customer.setFollowUpSuggestion(followUp);
        customer.setUpdatedAt(LocalDateTime.now());
        customerRepo.save(customer);

        return new AnalysisResult(profile, grade, gradeReason, followUp, costMs);
    }

    /**
     * 批量分析所有尚未分级的客户。
     *
     * @return 成功分析的客户数量
     */
    public int analyzeAllUngraded() {
        List<Customer> ungraded = customerRepo.findAll().stream()
                .filter(c -> c.getGrade() == null || c.getGrade().isBlank())
                .toList();

        int success = 0;
        for (Customer c : ungraded) {
            try {
                analyzeCustomer(c.getId());
                success++;
            } catch (Exception e) {
                log.warn("trade.analysis batch failed customerId={} err={}", c.getId(), e.getMessage());
            }
        }
        log.info("trade.analysis batch done total={} success={}", ungraded.size(), success);
        return success;
    }

    // ── Prompt 构建 ─────────────────────────────────────────────────────────

    private String buildAnalysisPrompt(Customer customer, List<CommunicationRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一位资深外贸客户分析顾问，擅长从有限的沟通记录中洞察客户的真实需求和合作潜力。
                
                ## 任务
                请基于以下客户信息和历史沟通记录，完成以下分析：
                
                ## 输出要求（严格按 JSON 格式输出，不要输出其他内容）
                ```json
                {
                  "profile": "客户画像：包含行业定位、企业规模判断、采购角色推测、核心需求、合作障碍分析",
                  "grade": "A/B/C/D",
                  "gradeReason": "分级理由，说明为何给出此等级",
                  "followUp": "跟进建议：包含推荐切入产品线、沟通话术要点、最佳联系时机、注意事项"
                }
                ```
                
                ## 分级标准
                - **A级**：明确采购意向 + 匹配度高 + 近期有成交可能
                - **B级**：有一定兴趣 + 需要培育 + 中期可能转化
                - **C级**：初步接触 + 信息不足 + 需进一步了解
                - **D级**：明确拒绝 / 竞品深度绑定 / 长期无响应
                
                ## 客户基本信息
                """);

        sb.append("- 公司名称：").append(nullSafe(customer.getCompanyName())).append("\n");
        sb.append("- 联系人：").append(nullSafe(customer.getContactName())).append("\n");
        sb.append("- 国家/地区：").append(nullSafe(customer.getCountry())).append("\n");
        sb.append("- 行业：").append(nullSafe(customer.getIndustry())).append("\n");
        sb.append("- 企业规模：").append(nullSafe(customer.getCompanySize())).append("\n");
        sb.append("- 感兴趣产品：").append(nullSafe(customer.getProductInterest())).append("\n");
        sb.append("- 客户来源：").append(nullSafe(customer.getSource())).append("\n");

        sb.append("\n## 历史沟通记录\n");
        if (records.isEmpty()) {
            sb.append("（暂无沟通记录，请基于客户基本信息进行初步判断）\n");
        } else {
            for (int i = 0; i < records.size(); i++) {
                CommunicationRecord r = records.get(i);
                sb.append(String.format("[%d] %s | %s | %s\n%s\n\n",
                        i + 1,
                        r.getCommunicatedAt() != null ? r.getCommunicatedAt().toString() : "未知时间",
                        nullSafe(r.getChannel()),
                        "inbound".equals(r.getDirection()) ? "客户→我方" : "我方→客户",
                        nullSafe(r.getContent())));
            }
        }

        return sb.toString();
    }

    // ── 响应解析 ─────────────────────────────────────────────────────────────

    private Map<String, String> parseLlmResponse(String response) {
        try {
            // 尝试提取 JSON 块
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String json = response.substring(start, end + 1);
                Map<String, Object> raw = MAPPER.readValue(json, new TypeReference<>() {});
                Map<String, String> result = new LinkedHashMap<>();
                raw.forEach((k, v) -> result.put(k, v != null ? v.toString() : ""));
                return result;
            }
        } catch (Exception e) {
            log.warn("trade.analysis json parse failed, using raw response err={}", e.getMessage());
        }
        // 解析失败时将整个响应作为 profile
        return Map.of("profile", response, "grade", "C", "gradeReason", "解析失败，默认C级", "followUp", response);
    }

    private static String nullSafe(String s) {
        return s == null || s.isBlank() ? "未知" : s;
    }
}
