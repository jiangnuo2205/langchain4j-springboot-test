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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 询盘/聊天记录导入服务
 * <p>
 * 支持三种数据来源：
 * <ol>
 *   <li>PDF文件（阿里国际站聊天截图/打印件）→ 调用本地OCR脚本提取文本</li>
 *   <li>文本粘贴（直接复制聊天记录）→ 跳过OCR直接分析</li>
 *   <li>Excel/CSV（阿里询盘导出）→ 后续可扩展</li>
 * </ol>
 * <p>
 * 核心流程：
 * <ol>
 *   <li>提取文本（OCR或直接输入）</li>
 *   <li>LLM识别双方对话：区分买方/卖方消息，提取时间线</li>
 *   <li>拆分存储为多条 CommunicationRecord（保留完整交流历史）</li>
 *   <li>同时提取客户基本信息 → 创建 Customer（未分级状态）</li>
 * </ol>
 */
@Service
public class InquiryImportService {

    private static final Logger log = LoggerFactory.getLogger(InquiryImportService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final CustomerRepository customerRepo;
    private final CommunicationRecordRepository recordRepo;

    public InquiryImportService(ChatModel chatModel,
                                CustomerRepository customerRepo,
                                CommunicationRecordRepository recordRepo) {
        this.chatModel = chatModel;
        this.customerRepo = customerRepo;
        this.recordRepo = recordRepo;
    }

    // ── 结果 Record ─────────────────────────────────────────────────────────

    public record ImportResult(
            Long customerId,                     // 新建或匹配到的客户ID
            String companyName,                  // 识别出的公司名
            int recordsCreated,                  // 创建的沟通记录数
            Map<String, Object> extractedInfo,   // LLM提取的结构化信息
            String rawText,                      // OCR提取的原始文本（或用户粘贴的文本）
            long ocrMs,                          // OCR耗时
            long llmMs                           // LLM分析耗时
    ) {}

    // ── 主方法1：从PDF导入 ──────────────────────────────────────────────────

    /**
     * 从PDF文件导入聊天记录。
     * 调用本地 Python OCR 脚本提取文本，然后 LLM 分析。
     *
     * @param pdfPath    PDF 文件路径
     * @param scriptPath OCR 脚本路径（pdf_ocr_to_txt.py）
     * @return 导入结果
     */
    public ImportResult importFromPdf(Path pdfPath, Path scriptPath) throws IOException {
        // 第一步：OCR 提取文本
        long ocrStart = System.nanoTime();
        String rawText = runOcrScript(pdfPath, scriptPath);
        long ocrMs = Duration.ofNanos(System.nanoTime() - ocrStart).toMillis();

        if (rawText == null || rawText.isBlank()) {
            throw new RuntimeException("OCR 未能提取到文本内容，请检查PDF文件");
        }

        log.info("inquiry.import ocr done pdfPath={} textLen={} ocrMs={}",
                pdfPath, rawText.length(), ocrMs);

        // 第二步：LLM 分析
        return analyzeAndSave(rawText, "pdf_import", ocrMs);
    }

    // ── 主方法2：从文本导入 ──────────────────────────────────────────────────

    /**
     * 从粘贴的文本导入聊天记录。
     * 用户直接复制阿里国际站/邮件/WhatsApp的聊天记录文本。
     *
     * @param rawText 聊天记录文本
     * @param channel 来源渠道：alibaba / email / whatsapp
     * @return 导入结果
     */
    public ImportResult importFromText(String rawText, String channel) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("文本内容不能为空");
        }
        return analyzeAndSave(rawText, channel, 0);
    }

    // ── 核心分析逻辑 ───────────────────────────────────────────────────────

    private ImportResult analyzeAndSave(String rawText, String source, long ocrMs) {
        // 截断过长的文本（避免超出 LLM context window）
        String textForAnalysis = rawText.length() > 8000
                ? rawText.substring(0, 8000) + "\n...(内容截断)"
                : rawText;

        String prompt = buildAnalysisPrompt(textForAnalysis);

        long llmStart = System.nanoTime();
        String llmResponse = chatModel.chat(prompt);
        long llmMs = Duration.ofNanos(System.nanoTime() - llmStart).toMillis();

        log.info("inquiry.import llm done source={} llmMs={}", source, llmMs);

        // 解析 LLM 响应
        Map<String, Object> extracted = parseLlmResponse(llmResponse);

        // 创建或匹配客户
        Customer customer = createOrMatchCustomer(extracted, source);

        // 存储沟通记录
        int recordsCreated = saveRecords(customer.getId(), extracted, source);

        return new ImportResult(
                customer.getId(), customer.getCompanyName(),
                recordsCreated, extracted, rawText, ocrMs, llmMs);
    }

    // ── Prompt 构建 ─────────────────────────────────────────────────────────

    private String buildAnalysisPrompt(String chatText) {
        return """
                你是一位资深外贸数据分析专家。以下是一段外贸聊天记录（可能来自阿里国际站、邮件或WhatsApp），
                可能包含OCR识别的噪声。请从中提取结构化信息。
                
                ## 任务
                1. 识别对话双方：区分买方（客户）和卖方（我方）的消息
                2. 提取客户基本信息（公司名、联系人、国家、行业等）
                3. 将对话按时间顺序拆分为独立的消息列表
                4. 判断当前沟通阶段和客户意向
                
                ## 判断买方/卖方的依据
                - 买方特征：询问价格、MOQ、交期、样品；提到自己的公司/需求
                - 卖方特征：报价、推荐产品、发送目录；提到"我们公司"、"our factory"
                - 如果无法区分，根据对话上下文和外贸常识推断
                
                ## 输出格式（严格JSON）
                ```json
                {
                  "customerInfo": {
                    "companyName": "客户公司名（如无法识别则写 '未知客户'）",
                    "contactName": "联系人姓名",
                    "email": "邮箱（如有）",
                    "country": "国家/地区",
                    "industry": "行业",
                    "productInterest": "感兴趣的产品"
                  },
                  "messages": [
                    {
                      "direction": "inbound 或 outbound",
                      "content": "消息内容（清理OCR噪声后的干净文本）",
                      "timestamp": "时间（如能识别，格式 yyyy-MM-dd HH:mm，否则为null）"
                    }
                  ],
                  "summary": {
                    "totalMessages": 5,
                    "stage": "初步接触 / 需求确认 / 报价样品 / 谈判中",
                    "customerIntent": "客户意向简述",
                    "keyProducts": ["提到的具体产品"],
                    "nextAction": "建议的下一步动作"
                  }
                }
                ```
                
                ## 聊天记录
                %s
                """.formatted(chatText);
    }

    // ── 客户创建/匹配 ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Customer createOrMatchCustomer(Map<String, Object> extracted, String source) {
        Map<String, Object> info = (Map<String, Object>) extracted.getOrDefault(
                "customerInfo", Map.of());

        String companyName = toStr(info.get("companyName"), "未知客户");

        // 尝试按公司名匹配已有客户
        List<Customer> existing = customerRepo.findAll().stream()
                .filter(c -> c.getCompanyName() != null &&
                        c.getCompanyName().equalsIgnoreCase(companyName))
                .toList();

        if (!existing.isEmpty()) {
            log.info("inquiry.import matched existing customer companyName='{}'", companyName);
            return existing.get(0);
        }

        // 创建新客户（未分级状态）
        Customer customer = new Customer();
        customer.setCompanyName(companyName);
        customer.setContactName(toStr(info.get("contactName"), null));
        customer.setEmail(toStr(info.get("email"), null));
        customer.setCountry(toStr(info.get("country"), null));
        customer.setIndustry(toStr(info.get("industry"), null));
        customer.setProductInterest(toStr(info.get("productInterest"), null));
        customer.setSource(source);
        // 不设置 grade，保持未分级状态
        customerRepo.save(customer);

        log.info("inquiry.import created customer id={} companyName='{}'",
                customer.getId(), companyName);
        return customer;
    }

    // ── 沟通记录存储 ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private int saveRecords(Long customerId, Map<String, Object> extracted, String source) {
        Object messagesObj = extracted.get("messages");
        if (!(messagesObj instanceof List<?> messagesList)) {
            // 如果解析不出独立消息，把整个内容作为一条记录
            CommunicationRecord r = new CommunicationRecord();
            r.setCustomerId(customerId);
            r.setChannel(source);
            r.setDirection("inbound");
            r.setContent(extracted.getOrDefault("rawContent", "").toString());
            recordRepo.save(r);
            return 1;
        }

        int count = 0;
        for (Object msgObj : messagesList) {
            if (!(msgObj instanceof Map<?, ?> msg)) continue;

            CommunicationRecord r = new CommunicationRecord();
            r.setCustomerId(customerId);
            r.setChannel(source);
            r.setDirection(toStr(msg.get("direction"), "inbound"));
            r.setContent(toStr(msg.get("content"), ""));

            // 尝试解析时间
            String ts = toStr(msg.get("timestamp"), null);
            if (ts != null && !ts.equals("null") && !ts.isBlank()) {
                try {
                    r.setCommunicatedAt(LocalDateTime.parse(
                            ts.replace(" ", "T")));
                } catch (Exception e) {
                    // 时间解析失败，使用当前时间
                    r.setCommunicatedAt(LocalDateTime.now());
                }
            }

            recordRepo.save(r);
            count++;
        }

        log.info("inquiry.import saved {} records for customerId={}", count, customerId);
        return count;
    }

    // ── OCR 脚本调用 ───────────────────────────────────────────────────────

    /**
     * 调用本地 Python OCR 脚本将 PDF 转为文本。
     * <p>
     * 脚本位于 tools/pdf_ocr_to_txt.py
     * 用法：python pdf_ocr_to_txt.py <源目录> <输出目录>
     */
    private String runOcrScript(Path pdfPath, Path scriptPath) throws IOException {
        // 创建临时目录
        Path srcDir = Files.createTempDirectory("ocr_src_");
        Path outDir = Files.createTempDirectory("ocr_out_");

        try {
            // 把 PDF 复制到临时源目录
            Files.copy(pdfPath, srcDir.resolve(pdfPath.getFileName()));

            // 调用 Python 脚本
            ProcessBuilder pb = new ProcessBuilder(
                    "python3", scriptPath.toString(),
                    srcDir.toString(), outDir.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            log.info("inquiry.import ocr script exitCode={} output={}",
                    exitCode, output.toString().trim());

            if (exitCode != 0) {
                throw new RuntimeException("OCR脚本执行失败: " + output);
            }

            // 读取输出文本文件
            String pdfStem = pdfPath.getFileName().toString()
                    .replaceAll("\\.[^.]+$", "");
            Path txtFile = outDir.resolve(pdfStem + ".txt");

            if (Files.exists(txtFile)) {
                return Files.readString(txtFile);
            }

            // 如果文件名不匹配，尝试读取目录中的第一个 txt
            return Files.list(outDir)
                    .filter(p -> p.toString().endsWith(".txt"))
                    .findFirst()
                    .map(p -> {
                        try { return Files.readString(p); }
                        catch (IOException e) { return ""; }
                    })
                    .orElse("");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OCR脚本被中断", e);
        } finally {
            // 清理临时目录
            deleteDir(srcDir);
            deleteDir(outDir);
        }
    }

    private void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); }
                        catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
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
            log.warn("inquiry.import json parse failed err={}", e.getMessage());
        }
        return Map.of("rawContent", response);
    }

    private static String toStr(Object o, String def) {
        if (o == null) return def;
        String s = o.toString().trim();
        return s.isEmpty() || "null".equals(s) ? def : s;
    }
}
