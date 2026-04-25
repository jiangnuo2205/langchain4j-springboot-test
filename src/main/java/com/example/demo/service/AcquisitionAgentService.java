
package com.example.demo.service;

import com.example.demo.entity.AgentMessage;
import com.example.demo.entity.AgentSession;
import com.example.demo.entity.SalespersonProfile;
import com.example.demo.repository.AgentMessageRepository;
import com.example.demo.repository.AgentSessionRepository;
import com.example.demo.repository.SalespersonProfileRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * 获客智能体 — 对话式Agent核心服务
 * <p>
 * 角色：辅助外贸人员寻找客户 + 辅助客户成交
 * <p>
 * 核心能力：
 * 1. 两层意图识别（L1表层分类 + L2深层目标）
 * 2. 实时评估体系（每条消息后更新）
 * 3. 上下文压缩（滑动窗口 + 定期摘要）
 * 4. 兜底拒绝（非业务内容不处理不计入）
 * 5. 业务员画像累积
 */
@Service
public class AcquisitionAgentService {

    private static final Logger log = LoggerFactory.getLogger(AcquisitionAgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 保留最近N条原始消息 */
    private static final int CONTEXT_WINDOW = 10;
    /** 每N轮执行一次摘要 */
    private static final int SUMMARY_INTERVAL = 10;

    private final ChatModel chatModel;
    private final AgentSessionRepository sessionRepo;
    private final AgentMessageRepository messageRepo;
    private final SalespersonProfileRepository profileRepo;

    public AcquisitionAgentService(ChatModel chatModel,
                                    AgentSessionRepository sessionRepo,
                                    AgentMessageRepository messageRepo,
                                    SalespersonProfileRepository profileRepo) {
        this.chatModel = chatModel;
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.profileRepo = profileRepo;
    }

    // ── 结果 Record ─────────────────────────────────────────────────────────

    public record AgentReply(
            Long sessionId,
            String reply,                    // AI 对话回复
            Map<String, Object> evaluation,  // 实时评估结果
            boolean validBusiness,           // 是否为有效业务对话
            long costMs
    ) {}

    // ── 主方法：处理一轮对话 ────────────────────────────────────────────────

    /**
     * 处理用户发送的一条消息，返回 AI 回复 + 实时评估。
     *
     * @param sessionId     会话ID（null 则创建新会话）
     * @param salespersonId 业务员ID
     * @param userMessage   用户消息
     * @return AI 回复和评估
     */
    public AgentReply chat(Long sessionId, String salespersonId, String userMessage) {
        // 获取或创建会话
        AgentSession session;
        if (sessionId != null) {
            session = sessionRepo.findById(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        } else {
            session = createSession(salespersonId, userMessage);
        }

        // 保存用户消息
        int seq = session.getMessageCount() + 1;
        saveMessage(session.getId(), "user", userMessage, null, true, seq);
        session.setMessageCount(seq);

        // 构建上下文
        List<ChatMessage> messages = buildContextMessages(session);

        // 调用 LLM
        long start = System.nanoTime();
        ChatResponse response = chatModel.chat(messages);
        long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        String rawReply = response.aiMessage().text();

        // 解析回复：分离对话内容和评估数据
        ParsedReply parsed = parseAgentReply(rawReply);

        // 判断是否为有效业务对话
        boolean valid = !"OFF_TOPIC".equals(
                parsed.evaluation.getOrDefault("intentL1", ""));

        // 保存 AI 回复
        int aiSeq = session.getMessageCount() + 1;
        String evalJson = toJson(parsed.evaluation);
        saveMessage(session.getId(), "assistant", parsed.reply, evalJson, valid, aiSeq);
        session.setMessageCount(aiSeq);

        // 更新会话状态
        String intentL1 = toStr(parsed.evaluation.get("intentL1"), session.getCurrentIntentL1());
        String intentL2 = toStr(parsed.evaluation.get("intentL2"), session.getCurrentIntentL2());
        if (valid) {
            session.setCurrentIntentL1(intentL1);
            session.setCurrentIntentL2(intentL2);
        }

        // 提取并累积客户信息
        Object customerInfo = parsed.evaluation.get("customerInfoExtracted");
        if (customerInfo != null) {
            session.setCustomerInfoSnapshot(toJson(customerInfo));
        }

        sessionRepo.save(session);

        // 检查是否需要压缩上下文
        if (session.getMessageCount() - session.getLastSummaryAtCount() >= SUMMARY_INTERVAL * 2) {
            compressContext(session);
        }

        // 更新业务员画像（异步，不阻塞）
        if (valid) {
            updateSalespersonProfile(salespersonId, parsed.evaluation);
        }

        log.info("agent.chat sessionId={} seq={} intentL1={} valid={} costMs={}",
                session.getId(), aiSeq, intentL1, valid, costMs);

        return new AgentReply(session.getId(), parsed.reply, parsed.evaluation, valid, costMs);
    }

    // ── 会话管理 ────────────────────────────────────────────────────────────

    private AgentSession createSession(String salespersonId, String firstMessage) {
        AgentSession session = new AgentSession();
        session.setSalespersonId(salespersonId);
        session.setTitle(firstMessage.length() > 50 ?
                firstMessage.substring(0, 50) + "..." : firstMessage);
        session.setMessageCount(0);
        session.setLastSummaryAtCount(0);
        return sessionRepo.save(session);
    }

    public List<AgentSession> listSessions(String salespersonId) {
        return sessionRepo.findBySalespersonIdOrderByUpdatedAtDesc(salespersonId);
    }

    public List<AgentMessage> getSessionMessages(Long sessionId) {
        return messageRepo.findBySessionIdOrderBySeqNumAsc(sessionId);
    }

    // ── 上下文构建 ──────────────────────────────────────────────────────────

    private List<ChatMessage> buildContextMessages(AgentSession session) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. System Prompt（角色 + 规则 + 输出格式）
        String systemPrompt = buildSystemPrompt(session);
        messages.add(new SystemMessage(systemPrompt));

        // 2. 历史上下文摘要（如果有）
        if (session.getContextSummary() != null && !session.getContextSummary().isBlank()) {
            messages.add(new SystemMessage(
                    "## 之前的对话摘要\n" + session.getContextSummary()));
        }

        // 3. 最近 N 条消息（滑动窗口）
        List<AgentMessage> recentMessages;
        int windowStart = Math.max(0, session.getMessageCount() - CONTEXT_WINDOW);
        if (windowStart > 0) {
            recentMessages = messageRepo.findBySessionIdAndSeqNumGreaterThanOrderBySeqNumAsc(
                    session.getId(), windowStart);
        } else {
            recentMessages = messageRepo.findBySessionIdOrderBySeqNumAsc(session.getId());
        }

        for (AgentMessage msg : recentMessages) {
            if ("user".equals(msg.getRole())) {
                messages.add(new UserMessage(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                messages.add(new AiMessage(msg.getContent()));
            }
        }

        return messages;
    }

    // ── System Prompt ───────────────────────────────────────────────────────

    private String buildSystemPrompt(AgentSession session) {
        // 获取业务员画像
        String profileStr = "";
        try {
            Optional<SalespersonProfile> profile =
                    profileRepo.findBySalespersonId(session.getSalespersonId());
            if (profile.isPresent()) {
                SalespersonProfile p = profile.get();
                profileStr = String.format(
                        "该业务员擅长品类：%s，擅长市场：%s，总会话：%d次",
                        nullSafe(p.getStrongCategories()),
                        nullSafe(p.getStrongMarkets()),
                        p.getTotalSessions());
            }
        } catch (Exception ignored) {}

        return """
                # 角色
                你是一位资深外贸获客顾问AI助手。你的职责是辅助外贸业务员寻找客户和促成成交。
                
                # 能力范围（严格遵守）
                你只处理以下三类业务意图，其他一切内容必须拒绝：
                
                ## 意图A：找客户
                业务员想在某个平台/渠道找到某类客户。
                你需要：提供具体的平台选择、搜索策略、筛选标准、首次触达话术。
                
                ## 意图B：促成交
                业务员正在与某个客户沟通中遇到问题，需要你帮忙解决。
                你需要：分析客户的深层顾虑（资金安全/风险/信任/竞品对比等），提供话术建议和策略方案。
                
                ## 意图C：创意获客
                业务员有奇思妙想，想要找到创新的获客方式或产品匹配点。
                你需要：联想创意方案、定制化信息源建议、社媒内容策略、跨界产品匹配。
                
                ## 兜底规则
                如果用户消息不属于以上三类，你必须回复：
                "抱歉，我是外贸获客助手，只能帮您处理：1. 寻找目标客户 2. 促成客户成交 3. 创意获客方案。请描述您的具体业务需求。"
                兜底回复时，intentL1 必须为 "OFF_TOPIC"。
                
                # 回复格式（严格遵守）
                你的每条回复必须包含两个部分，用 ===EVALUATION=== 分隔：
                
                第一部分：给业务员的自然语言回复（策略建议/话术/创意方案）
                
                ===EVALUATION===
                
                第二部分：JSON格式的评估数据（不要用markdown代码块）
                {
                  "intentL1": "FIND_CUSTOMER / CLOSE_DEAL / CREATIVE / OFF_TOPIC",
                  "intentL1Label": "找客户 / 促成交 / 创意获客 / 非业务",
                  "intentL2": "深层目标描述（如：客户担心质量不稳定→核心顾虑=供应链风险）",
                  "intentL2Tags": ["资金安全", "风险承受", "信任建立"],
                  "customerInfoExtracted": {
                    "companyName": "如果提到了客户公司名",
                    "country": "如果提到了国家",
                    "productInterest": "如果提到了产品",
                    "concerns": "客户顾虑"
                  },
                  "salespersonEval": {
                    "currentSkillShown": "本轮展现的能力（如：需求识别、异议处理）",
                    "improvementHint": "技能提升建议",
                    "experienceLevel": "BEGINNER / INTERMEDIATE / ADVANCED"
                  },
                  "nextStepGuide": "下一步指引：具体该做什么",
                  "replySuggestion": "给业务员的回复建议模板（用于回复客户）",
                  "similarCaseHint": "同类客户处理经验参考"
                }
                
                # 业务员信息
                %s
                
                # 当前对话状态
                当前意图：%s
                涉及客户：%s
                """.formatted(
                profileStr.isEmpty() ? "暂无画像数据" : profileStr,
                nullSafe(session.getCurrentIntentL1()),
                nullSafe(session.getCustomerInfoSnapshot()));
    }

    // ── 回复解析 ────────────────────────────────────────────────────────────

    private record ParsedReply(String reply, Map<String, Object> evaluation) {}

    private ParsedReply parseAgentReply(String rawReply) {
        String separator = "===EVALUATION===";
        int sepIndex = rawReply.indexOf(separator);

        if (sepIndex < 0) {
            // 没有评估部分，可能是兜底回复
            return new ParsedReply(rawReply.trim(), Map.of(
                    "intentL1", "OFF_TOPIC",
                    "intentL1Label", "无法解析",
                    "nextStepGuide", "请描述您的业务需求"
            ));
        }

        String reply = rawReply.substring(0, sepIndex).trim();
        String evalStr = rawReply.substring(sepIndex + separator.length()).trim();

        Map<String, Object> evaluation;
        try {
            int start = evalStr.indexOf('{');
            int end = evalStr.lastIndexOf('}');
            if (start >= 0 && end > start) {
                evaluation = MAPPER.readValue(
                        evalStr.substring(start, end + 1), new TypeReference<>() {});
            } else {
                evaluation = Map.of("intentL1", "UNKNOWN", "raw", evalStr);
            }
        } catch (Exception e) {
            log.warn("agent.parse eval failed err={}", e.getMessage());
            evaluation = Map.of("intentL1", "UNKNOWN", "raw", evalStr);
        }

        return new ParsedReply(reply, evaluation);
    }

    // ── 上下文压缩 ──────────────────────────────────────────────────────────

    private void compressContext(AgentSession session) {
        try {
            // 取出需要压缩的消息（从上次摘要到窗口开始之间的消息）
            int windowStart = session.getMessageCount() - CONTEXT_WINDOW;
            List<AgentMessage> toCompress = messageRepo
                    .findBySessionIdAndSeqNumGreaterThanOrderBySeqNumAsc(
                            session.getId(), session.getLastSummaryAtCount())
                    .stream()
                    .filter(m -> m.getSeqNum() <= windowStart)
                    .toList();

            if (toCompress.size() < 4) return;

            StringBuilder content = new StringBuilder();
            for (AgentMessage m : toCompress) {
                content.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
            }

            String summaryPrompt = """
                    请将以下外贸业务对话压缩为一段简洁的摘要（不超过300字），
                    保留：关键的业务决策、客户信息、意图变化、重要建议。
                    丢弃：寒暄、重复内容、格式化文本。
                    
                    对话内容：
                    %s
                    """.formatted(content.toString());

            String summary = chatModel.chat(summaryPrompt);

            // 合并旧摘要
            String existing = session.getContextSummary();
            if (existing != null && !existing.isBlank()) {
                summary = existing + "\n---\n" + summary;
                // 如果摘要本身太长，再次压缩
                if (summary.length() > 1500) {
                    summary = chatModel.chat("请将以下摘要精简到300字以内：\n" + summary);
                }
            }

            session.setContextSummary(summary);
            session.setLastSummaryAtCount(windowStart);
            sessionRepo.save(session);

            log.info("agent.compress sessionId={} compressed {} messages, summaryLen={}",
                    session.getId(), toCompress.size(), summary.length());
        } catch (Exception e) {
            log.warn("agent.compress failed sessionId={} err={}", session.getId(), e.getMessage());
        }
    }

    // ── 业务员画像更新 ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void updateSalespersonProfile(String salespersonId, Map<String, Object> evaluation) {
        try {
            SalespersonProfile profile = profileRepo.findBySalespersonId(salespersonId)
                    .orElseGet(() -> {
                        SalespersonProfile p = new SalespersonProfile();
                        p.setSalespersonId(salespersonId);
                        p.setTotalSessions(0);
                        p.setTotalValidTurns(0);
                        return p;
                    });

            profile.setTotalValidTurns(profile.getTotalValidTurns() + 1);

            // 从评估中提取技能信息
            Object spEval = evaluation.get("salespersonEval");
            if (spEval instanceof Map<?, ?> evalMap) {
                String skill = toStr(evalMap.get("currentSkillShown"), null);
                String hint = toStr(evalMap.get("improvementHint"), null);
                if (skill != null) {
                    profile.setAbilityProfile(skill);
                }
                if (hint != null) {
                    profile.setWeaknesses(hint);
                }
            }

            profileRepo.save(profile);
        } catch (Exception e) {
            log.warn("agent.profile update failed err={}", e.getMessage());
        }
    }

    // ── 工具方法 ────────────────────────────────────────────────────────────

    private void saveMessage(Long sessionId, String role, String content,
                             String evaluation, boolean valid, int seqNum) {
        AgentMessage msg = new AgentMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setEvaluation(evaluation);
        msg.setValidBusiness(valid);
        msg.setSeqNum(seqNum);
        messageRepo.save(msg);
    }

    private String toJson(Object obj) {
        try { return MAPPER.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    private static String toStr(Object o, String def) {
        if (o == null) return def;
        String s = o.toString().trim();
        return s.isEmpty() || "null".equals(s) ? def : s;
    }

    private static String nullSafe(String s) {
        return s == null || s.isBlank() ? "未知" : s;
    }
}
