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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 客户雷达监测 + 联络节点 + 成交抵达度评估
 * <p>
 * 核心功能：
 * <ol>
 *   <li>离线雷达：分析历史沟通记录，提取情感/需求/响应速度等信号</li>
 *   <li>实时雷达：对比客户官网信息变化，识别切入机会</li>
 *   <li>成交抵达度：基于信号加权计算 0-100 分</li>
 *   <li>联络节点：基于当前阶段 + 雷达信号，计算下次联系时间和方式</li>
 * </ol>
 */
@Service
public class CustomerRadarService {

    private static final Logger log = LoggerFactory.getLogger(CustomerRadarService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final CustomerRepository customerRepo;
    private final CommunicationRecordRepository recordRepo;

    public CustomerRadarService(ChatModel chatModel,
                                CustomerRepository customerRepo,
                                CommunicationRecordRepository recordRepo) {
        this.chatModel = chatModel;
        this.customerRepo = customerRepo;
        this.recordRepo = recordRepo;
    }

    // ── 结果 Records ─────────────────────────────────────────────────────────

    /**
     * 完整的客户雷达评估结果
     */
    public record RadarResult(
            Long customerId,
            String companyName,
            int dealReadiness,              // 成交抵达度 0-100
            String dealStage,               // 当前阶段名称
            String contactSchedule,         // 联络节点建议
            LocalDateTime nextContactAt,    // 建议下次联系时间
            String contactMethod,           // 建议联系方式
            String contactTopic,            // 建议联系话题
            List<RadarSignal> signals,      // 检测到的信号列表
            String aiInsight,               // AI 深度分析洞察
            long costMs
    ) {}

    /**
     * 单个雷达信号
     */
    public record RadarSignal(
            String type,         // positive / negative / opportunity
            String source,       // offline / realtime
            String description,  // 信号描述
            int weight           // 权重影响值
    ) {}

    // ── 主方法：全链路雷达评估 ──────────────────────────────────────────────

    /**
     * 对指定客户执行完整的雷达评估：
     * 历史分析 → 信号识别 → 成交抵达度 → 联络节点
     */
    public RadarResult evaluateCustomer(Long customerId) {
        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        List<CommunicationRecord> records =
                recordRepo.findByCustomerIdOrderByCommunicatedAtDesc(customerId);

        // 构建 Prompt 让 LLM 做综合分析
        String prompt = buildRadarPrompt(customer, records);

        long start = System.nanoTime();
        String llmResponse = chatModel.chat(prompt);
        long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        log.info("radar.evaluate customerId={} companyName='{}' costMs={}",
                customerId, customer.getCompanyName(), costMs);

        // 解析 LLM 响应
        Map<String, Object> parsed = parseLlmResponse(llmResponse);

        // 提取结果
        int dealReadiness = toInt(parsed.get("dealReadiness"), 20);
        String dealStage = toStr(parsed.get("dealStage"), "初步接触");
        String contactSchedule = toStr(parsed.get("contactSchedule"), "3天后跟进");
        String contactMethod = toStr(parsed.get("contactMethod"), "email");
        String contactTopic = toStr(parsed.get("contactTopic"), "");
        String aiInsight = toStr(parsed.get("aiInsight"), llmResponse);

        // 解析信号列表
        List<RadarSignal> signals = parseSignals(parsed.get("signals"));

        // 计算下次联系时间
        LocalDateTime nextContactAt = calculateNextContactTime(
                dealStage, records, customer.getLastFollowUpAt());

        // 更新数据库中的客户信息
        customer.setFollowUpSuggestion(contactTopic + " | " + contactSchedule);
        customerRepo.save(customer);

        return new RadarResult(
                customerId, customer.getCompanyName(),
                dealReadiness, dealStage,
                contactSchedule, nextContactAt, contactMethod, contactTopic,
                signals, aiInsight, costMs);
    }

    /**
     * 批量评估所有已分级客户，返回按紧急度排序的列表
     */
    public List<RadarResult> evaluateAllAndRank() {
        List<Customer> customers = customerRepo.findAll().stream()
                .filter(c -> c.getGrade() != null && !c.getGrade().isBlank())
                .toList();

        List<RadarResult> results = new ArrayList<>();
        for (Customer c : customers) {
            try {
                results.add(evaluateCustomer(c.getId()));
            } catch (Exception e) {
                log.warn("radar.evaluate batch failed customerId={} err={}", c.getId(), e.getMessage());
            }
        }

        // 按成交抵达度降序 + 联络紧急度排序
        results.sort((a, b) -> {
            // 先看是否到了联络节点
            boolean aUrgent = a.nextContactAt() != null &&
                    a.nextContactAt().isBefore(LocalDateTime.now().plusDays(1));
            boolean bUrgent = b.nextContactAt() != null &&
                    b.nextContactAt().isBefore(LocalDateTime.now().plusDays(1));
            if (aUrgent != bUrgent) return aUrgent ? -1 : 1;
            // 再按成交抵达度
            return Integer.compare(b.dealReadiness(), a.dealReadiness());
        });

        return results;
    }

    // ── Prompt 构建 ─────────────────────────────────────────────────────────

    private String buildRadarPrompt(Customer customer, List<CommunicationRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一位资深外贸销售顾问和数据分析专家。请基于以下客户信息和沟通记录，
                完成客户状态的全面评估。
                
                ## 任务
                1. 分析历史沟通记录，识别关键信号（正面/负面/机会）
                2. 评估成交抵达度（0-100%）
                3. 判断当前所处的销售阶段
                4. 计算最佳联络节点（何时、用什么方式、聊什么话题跟进）
                
                ## 成交阶段定义
                - 初步接触(20%)：仅有初次询盘，信息模糊
                - 需求确认(40%)：客户明确了品类/数量/要了目录
                - 报价样品(60%)：已报价或寄样
                - 谈判中(80%)：讨论价格/付款/交期/条款
                - 成交(100%)：下单
                
                ## 联络节点规则
                - 首次触达后 → 24小时内必须回复
                - 深度沟通阶段 → 每3-5天跟进
                - 报价/样品阶段 → 7天后催促
                - 谈判阶段 → 按约定时间
                - 如果发现机会信号（如客户官网变化、行业动态），建议立即联系
                
                ## 信号类型
                - positive：积极信号（询问细节、要样品、回复快）
                - negative：消极信号（长期不回、明确拒绝、竞品锁定）
                - opportunity：机会信号（需求变化、切入窗口）
                
                ## 输出格式（严格JSON）
                ```json
                {
                  "dealReadiness": 60,
                  "dealStage": "报价样品",
                  "signals": [
                    {"type": "positive", "source": "offline", "description": "客户主动询问样品", "weight": 15},
                    {"type": "opportunity", "source": "offline", "description": "客户提到原供应商质量问题", "weight": 25}
                  ],
                  "contactSchedule": "建议3天内跟进",
                  "contactMethod": "email",
                  "contactTopic": "以样品反馈为切入点，顺势推荐关联产品线",
                  "aiInsight": "该客户处于供应商切换窗口期，原供应商质量下降是核心痛点。建议以品质保障为核心卖点切入，附带SGS检测报告增加信任。跟进时避免过于推销，以专业服务建立信任。"
                }
                ```
                
                ## 客户信息
                """);

        sb.append("- 公司：").append(nullSafe(customer.getCompanyName())).append("\n");
        sb.append("- 国家：").append(nullSafe(customer.getCountry())).append("\n");
        sb.append("- 行业：").append(nullSafe(customer.getIndustry())).append("\n");
        sb.append("- 规模：").append(nullSafe(customer.getCompanySize())).append("\n");
        sb.append("- 感兴趣产品：").append(nullSafe(customer.getProductInterest())).append("\n");
        sb.append("- 来源：").append(nullSafe(customer.getSource())).append("\n");
        sb.append("- 当前等级：").append(nullSafe(customer.getGrade())).append("\n");

        if (customer.getAiProfile() != null && !customer.getAiProfile().isBlank()) {
            sb.append("- AI画像：").append(customer.getAiProfile()).append("\n");
        }

        sb.append("\n## 沟通记录（按时间倒序）\n");
        if (records.isEmpty()) {
            sb.append("（暂无沟通记录）\n");
        } else {
            for (int i = 0; i < records.size(); i++) {
                CommunicationRecord r = records.get(i);
                String time = r.getCommunicatedAt() != null ?
                        r.getCommunicatedAt().toString() : "未知时间";
                String dir = "inbound".equals(r.getDirection()) ? "客户→我方" : "我方→客户";
                sb.append(String.format("[%d] %s | %s | %s\n%s\n\n",
                        i + 1, time, nullSafe(r.getChannel()), dir,
                        nullSafe(r.getContent())));
            }

            // 增加时间维度分析上下文
            if (!records.isEmpty()) {
                CommunicationRecord latest = records.get(0);
                CommunicationRecord earliest = records.get(records.size() - 1);
                if (latest.getCommunicatedAt() != null) {
                    long daysSinceLastContact = ChronoUnit.DAYS.between(
                            latest.getCommunicatedAt(), LocalDateTime.now());
                    sb.append("距最后一次沟通：").append(daysSinceLastContact).append("天\n");
                }
                sb.append("总沟通次数：").append(records.size()).append("\n");
                long inboundCount = records.stream()
                        .filter(r -> "inbound".equals(r.getDirection())).count();
                sb.append("客户主动联系次数：").append(inboundCount).append("\n");
            }
        }

        return sb.toString();
    }

    // ── 联络时间计算 ────────────────────────────────────────────────────────

    private LocalDateTime calculateNextContactTime(
            String dealStage, List<CommunicationRecord> records, LocalDateTime lastFollowUp) {

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastContact = null;

        // 取最近的沟通时间
        if (!records.isEmpty() && records.get(0).getCommunicatedAt() != null) {
            lastContact = records.get(0).getCommunicatedAt();
        }
        if (lastFollowUp != null && (lastContact == null || lastFollowUp.isAfter(lastContact))) {
            lastContact = lastFollowUp;
        }

        // 如果没有任何联系记录，立即联系
        if (lastContact == null) {
            return now;
        }

        // 根据阶段确定跟进间隔
        long intervalDays = switch (dealStage) {
            case "初步接触" -> 1;   // 24小时
            case "需求确认" -> 3;   // 3天
            case "报价样品" -> 7;   // 7天
            case "谈判中" -> 5;     // 5天
            default -> 3;
        };

        LocalDateTime nextContact = lastContact.plusDays(intervalDays);

        // 如果计算出的时间已经过去，返回"现在就该联系"
        if (nextContact.isBefore(now)) {
            return now;
        }

        return nextContact;
    }

    // ── 解析工具 ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseLlmResponse(String response) {
        try {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String json = response.substring(start, end + 1);
                return MAPPER.readValue(json, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("radar.evaluate json parse failed err={}", e.getMessage());
        }
        return Map.of(
                "dealReadiness", 20,
                "dealStage", "初步接触",
                "aiInsight", response,
                "contactSchedule", "尽快跟进",
                "contactMethod", "email",
                "contactTopic", "初步了解需求"
        );
    }

    @SuppressWarnings("unchecked")
    private List<RadarSignal> parseSignals(Object signalsObj) {
        if (signalsObj == null) return List.of();
        try {
            if (signalsObj instanceof List<?> list) {
                List<RadarSignal> signals = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        signals.add(new RadarSignal(
                                toStr(map.get("type"), "positive"),
                                toStr(map.get("source"), "offline"),
                                toStr(map.get("description"), ""),
                                toInt(map.get("weight"), 10)
                        ));
                    }
                }
                return signals;
            }
        } catch (Exception e) {
            log.warn("radar.evaluate signals parse failed", e);
        }
        return List.of();
    }

    private static String nullSafe(String s) {
        return s == null || s.isBlank() ? "未知" : s;
    }

    private static String toStr(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private static int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); }
        catch (Exception e) { return def; }
    }
}
