package com.example.demo.web;

import com.example.demo.entity.CommunicationRecord;
import com.example.demo.entity.Customer;
import com.example.demo.repository.CommunicationRecordRepository;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.service.CustomerAnalysisService;
import com.example.demo.service.CustomerRadarService;
import com.example.demo.service.InquiryImportService;
import com.example.demo.service.IntentRecognitionService;
import com.example.demo.service.WebsiteAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外贸智能体 API
 * <p>
 * 提供转化智能体（客户分析/分级/跟进）、获客智能体（意图识别）
 * 和网站分析（URL→客户画像）的 REST 接口。
 */
@RestController
@RequestMapping("/api/trade")
@CrossOrigin(origins = "*")
public class TradeController {

    private static final Logger log = LoggerFactory.getLogger(TradeController.class);

    private final CustomerRepository customerRepo;
    private final CommunicationRecordRepository recordRepo;
    private final CustomerAnalysisService analysisService;
    private final IntentRecognitionService intentService;
    private final WebsiteAnalysisService websiteAnalysisService;
    private final CustomerRadarService radarService;
    private final InquiryImportService inquiryImportService;

    public TradeController(CustomerRepository customerRepo,
                           CommunicationRecordRepository recordRepo,
                           CustomerAnalysisService analysisService,
                           IntentRecognitionService intentService,
                           WebsiteAnalysisService websiteAnalysisService,
                           CustomerRadarService radarService,
                           InquiryImportService inquiryImportService) {
        this.customerRepo = customerRepo;
        this.recordRepo = recordRepo;
        this.analysisService = analysisService;
        this.intentService = intentService;
        this.websiteAnalysisService = websiteAnalysisService;
        this.radarService = radarService;
        this.inquiryImportService = inquiryImportService;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 客户 CRUD
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/customers")
    public List<Customer> listCustomers(@RequestParam(required = false) String grade) {
        if (grade != null && !grade.isBlank()) {
            return customerRepo.findByGradeOrderByUpdatedAtDesc(grade.toUpperCase());
        }
        return customerRepo.findAllByOrderByUpdatedAtDesc();
    }

    @GetMapping("/customers/{id}")
    public ResponseEntity<Map<String, Object>> getCustomer(@PathVariable Long id) {
        return customerRepo.findById(id)
                .map(customer -> {
                    List<CommunicationRecord> records =
                            recordRepo.findByCustomerIdOrderByCommunicatedAtDesc(id);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("customer", customer);
                    result.put("records", records);
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/customers")
    public Customer createCustomer(@RequestBody Customer customer) {
        return customerRepo.save(customer);
    }

    @PostMapping("/customers/{customerId}/records")
    public CommunicationRecord addRecord(
            @PathVariable Long customerId,
            @RequestBody CommunicationRecord record) {
        customerRepo.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));
        record.setCustomerId(customerId);
        return recordRepo.save(record);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 转化智能体
    // ═══════════════════════════════════════════════════════════════════════

    @PostMapping("/customers/{id}/analyze")
    public ResponseEntity<Map<String, Object>> analyzeCustomer(@PathVariable Long id) {
        try {
            CustomerAnalysisService.AnalysisResult result = analysisService.analyzeCustomer(id);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("customerId", id);
            response.put("profile", result.profile());
            response.put("grade", result.grade());
            response.put("gradeReason", result.gradeReason());
            response.put("followUp", result.followUp());
            response.put("costMs", result.costMs());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("trade.analyze failed customerId={} err={}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/analyze-all")
    public Map<String, Object> analyzeAll() {
        int count = analysisService.analyzeAllUngraded();
        return Map.of("analyzed", count);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 获客智能体
    // ═══════════════════════════════════════════════════════════════════════

    @PostMapping("/intent")
    public Map<String, Object> recognizeIntent(@RequestBody Map<String, String> body) {
        String input = body.getOrDefault("input", "");
        if (input.isBlank()) {
            return Map.of("error", "input is required");
        }
        IntentRecognitionService.IntentResult result = intentService.recognizeIntent(input);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("parsed", result.parsed());
        response.put("rawResponse", result.rawResponse());
        response.put("costMs", result.costMs());
        return response;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 网站分析（URL → 客户画像 → 存数据库）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 网站分析：输入客户网站 URL → 爬取内容 → AI 生成客户画像 → 存入数据库
     * <p>
     * POST /api/trade/analyze-website
     * Body: { "url": "https://www.leonardo.co.uk/" }
     */
    @PostMapping("/analyze-website")
    public ResponseEntity<Map<String, Object>> analyzeWebsite(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("url", "");
        if (url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url is required"));
        }
        try {
            WebsiteAnalysisService.WebsiteAnalysisResult result =
                    websiteAnalysisService.analyzeWebsite(url);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("customerId", result.customerId());
            response.put("profile", result.profile());
            response.put("pagesCrawled", result.pagesCrawled());
            response.put("crawlMs", result.crawlMs());
            response.put("llmMs", result.llmMs());
            response.put("totalMs", result.crawlMs() + result.llmMs());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("trade.website-analysis failed url={} err={}", url, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 雷达监测 + 成交抵达度 + 联络节点
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 单个客户雷达评估：成交抵达度 + 联络节点 + 信号分析
     * <p>
     * POST /api/trade/customers/{id}/radar
     */
    @PostMapping("/customers/{id}/radar")
    public ResponseEntity<Map<String, Object>> radarEvaluate(@PathVariable Long id) {
        try {
            CustomerRadarService.RadarResult result = radarService.evaluateCustomer(id);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("customerId", result.customerId());
            response.put("companyName", result.companyName());
            response.put("dealReadiness", result.dealReadiness());
            response.put("dealStage", result.dealStage());
            response.put("contactSchedule", result.contactSchedule());
            response.put("nextContactAt", result.nextContactAt() != null ? result.nextContactAt().toString() : null);
            response.put("contactMethod", result.contactMethod());
            response.put("contactTopic", result.contactTopic());
            response.put("signals", result.signals());
            response.put("aiInsight", result.aiInsight());
            response.put("costMs", result.costMs());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("radar.evaluate failed customerId={} err={}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 批量雷达评估：所有已分级客户，按紧急度排序
     * <p>
     * POST /api/trade/radar-all
     */
    @PostMapping("/radar-all")
    public List<Map<String, Object>> radarAll() {
        List<CustomerRadarService.RadarResult> results = radarService.evaluateAllAndRank();
        return results.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("customerId", r.customerId());
            m.put("companyName", r.companyName());
            m.put("dealReadiness", r.dealReadiness());
            m.put("dealStage", r.dealStage());
            m.put("contactSchedule", r.contactSchedule());
            m.put("nextContactAt", r.nextContactAt() != null ? r.nextContactAt().toString() : null);
            m.put("contactMethod", r.contactMethod());
            m.put("contactTopic", r.contactTopic());
            m.put("signals", r.signals());
            return m;
        }).toList();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 询盘/聊天记录导入
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 上传PDF → OCR + LLM → 创建客户和沟通记录
     */
    @PostMapping("/import-pdf")
    public ResponseEntity<Map<String, Object>> importPdf(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请上传PDF文件"));
        }
        try {
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("inquiry_", ".pdf");
            file.transferTo(tempFile.toFile());
            java.nio.file.Path scriptPath = java.nio.file.Path.of("tools", "pdf_ocr_to_txt.py");

            InquiryImportService.ImportResult result =
                    inquiryImportService.importFromPdf(tempFile, scriptPath);
            java.nio.file.Files.deleteIfExists(tempFile);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("customerId", result.customerId());
            response.put("companyName", result.companyName());
            response.put("recordsCreated", result.recordsCreated());
            response.put("extractedInfo", result.extractedInfo());
            response.put("ocrMs", result.ocrMs());
            response.put("llmMs", result.llmMs());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("import-pdf failed err={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 粘贴文本 → LLM分析 → 创建客户和沟通记录
     */
    @PostMapping("/import-text")
    public ResponseEntity<Map<String, Object>> importText(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        String channel = body.getOrDefault("channel", "alibaba");
        if (text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        try {
            InquiryImportService.ImportResult result =
                    inquiryImportService.importFromText(text, channel);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("customerId", result.customerId());
            response.put("companyName", result.companyName());
            response.put("recordsCreated", result.recordsCreated());
            response.put("extractedInfo", result.extractedInfo());
            response.put("llmMs", result.llmMs());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("import-text failed err={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 统计概览
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        List<Customer> all = customerRepo.findAll();
        long total = all.size();
        long gradeA = all.stream().filter(c -> "A".equals(c.getGrade())).count();
        long gradeB = all.stream().filter(c -> "B".equals(c.getGrade())).count();
        long gradeC = all.stream().filter(c -> "C".equals(c.getGrade())).count();
        long gradeD = all.stream().filter(c -> "D".equals(c.getGrade())).count();
        long ungraded = all.stream().filter(c -> c.getGrade() == null || c.getGrade().isBlank()).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCustomers", total);
        result.put("gradeA", gradeA);
        result.put("gradeB", gradeB);
        result.put("gradeC", gradeC);
        result.put("gradeD", gradeD);
        result.put("ungraded", ungraded);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Mock 数据初始化
    // ═══════════════════════════════════════════════════════════════════════

    @PostMapping("/init-demo")
    public Map<String, Object> initDemoData() {
        if (customerRepo.count() > 0) {
            return Map.of("message", "Demo data already exists", "customers", customerRepo.count());
        }

        // ── 客户 1：高意向美国零售商 ──
        Customer c1 = new Customer();
        c1.setCompanyName("GreenLife Outdoor LLC");
        c1.setContactName("Mike Johnson");
        c1.setEmail("mike@greenlifeoutdoor.com");
        c1.setCountry("United States");
        c1.setIndustry("Outdoor & Camping");
        c1.setCompanySize("50-100人");
        c1.setProductInterest("保温杯、户外杯垫、露营餐具");
        c1.setSource("alibaba");
        customerRepo.save(c1);
        addDemoRecord(c1.getId(), "alibaba", "inbound",
                "Hi, we are looking for custom insulated tumblers with our brand logo. We need 5000 pieces for our retail stores across the US. Can you provide samples?");
        addDemoRecord(c1.getId(), "email", "outbound",
                "Dear Mike, Thank you for your inquiry. We can absolutely customize insulated tumblers with your logo. Our MOQ is 1000 pieces. I've attached our catalog and price list. Would you like to discuss further?");
        addDemoRecord(c1.getId(), "email", "inbound",
                "Thanks for the quick response. The prices look reasonable. We'd also be interested in camping coasters. Can you send samples of both? We need them by next month for a buyer meeting.");

        // ── 客户 2：英国宠物用品经销商 ──
        Customer c2 = new Customer();
        c2.setCompanyName("PetHaven Trading UK");
        c2.setContactName("Sarah Williams");
        c2.setEmail("sarah@pethaven.co.uk");
        c2.setCountry("United Kingdom");
        c2.setIndustry("Pet Supplies");
        c2.setCompanySize("20-50人");
        c2.setProductInterest("宠物纪念品、定制宠物杯垫");
        c2.setSource("exhibition");
        customerRepo.save(c2);
        addDemoRecord(c2.getId(), "whatsapp", "inbound",
                "Hi, we met at the Canton Fair. We're interested in your pet memorial coasters. Do you have a catalog for pet-themed products?");
        addDemoRecord(c2.getId(), "email", "outbound",
                "Dear Sarah, Great to connect after the Canton Fair! I've attached our pet memorial product line. We can customize with pet photos and names. Our bestseller is the ceramic pet memorial coaster set.");

        // ── 客户 3：澳洲瑜伽品牌（冷淡） ──
        Customer c3 = new Customer();
        c3.setCompanyName("ZenFlow Yoga AU");
        c3.setContactName("Emma Chen");
        c3.setEmail("emma@zenflow.com.au");
        c3.setCountry("Australia");
        c3.setIndustry("Yoga & Fitness");
        c3.setCompanySize("10-20人");
        c3.setProductInterest("瑜伽垫、防滑瑜伽袜");
        c3.setSource("email");
        customerRepo.save(c3);
        addDemoRecord(c3.getId(), "email", "inbound",
                "Hello, we are looking for yoga mat suppliers. What's your pricing for 500 pieces?");
        addDemoRecord(c3.getId(), "email", "outbound",
                "Hi Emma, Thanks for reaching out! Here's our yoga mat pricing sheet. We offer eco-friendly TPE and natural rubber options. Would you prefer a video call to discuss details?");
        addDemoRecord(c3.getId(), "email", "inbound",
                "Thanks, we'll review and get back to you.");

        // ── 客户 4：德国工业采购商（换供应商信号） ──
        Customer c4 = new Customer();
        c4.setCompanyName("Müller Industriebedarf GmbH");
        c4.setContactName("Hans Müller");
        c4.setEmail("h.mueller@mueller-industrie.de");
        c4.setCountry("Germany");
        c4.setIndustry("Industrial Supplies");
        c4.setCompanySize("100-500人");
        c4.setProductInterest("工业防护手套、安全用品");
        c4.setSource("alibaba");
        customerRepo.save(c4);
        addDemoRecord(c4.getId(), "alibaba", "inbound",
                "We need 10,000 pairs of cut-resistant gloves, EN388 certified. Please send test report and FOB Shenzhen price.");
        addDemoRecord(c4.getId(), "email", "outbound",
                "Dear Mr. Müller, We have EN388 Level 5 cut-resistant gloves in stock. I've attached the SGS test report and our price list. FOB Shenzhen $2.80/pair for 10K pieces. Lead time: 25 days.");
        addDemoRecord(c4.getId(), "email", "inbound",
                "Price is acceptable. We already have a supplier in Dongguan but their quality dropped recently. Can you provide 200 pieces for testing first?");

        // ── 客户 5：日本礼品公司（长期无响应） ──
        Customer c5 = new Customer();
        c5.setCompanyName("Sakura Gift Co., Ltd");
        c5.setContactName("Tanaka Yuki");
        c5.setEmail("tanaka@sakuragift.jp");
        c5.setCountry("Japan");
        c5.setIndustry("Gifts & Souvenirs");
        c5.setCompanySize("30-50人");
        c5.setProductInterest("定制马克杯、节日礼品套装");
        c5.setSource("alibaba");
        customerRepo.save(c5);
        addDemoRecord(c5.getId(), "alibaba", "inbound",
                "カスタムマグカップの見積もりをお願いします。500個、桜柄のデザインで。");
        addDemoRecord(c5.getId(), "email", "outbound",
                "Dear Tanaka-san, Thank you for your inquiry about custom cherry blossom mugs. We've prepared a quotation for 500 pieces. Please find attached. We also offer gift set packaging if interested.");

        return Map.of("message", "Demo data initialized", "customers", 5);
    }

    private void addDemoRecord(Long customerId, String channel, String direction, String content) {
        CommunicationRecord r = new CommunicationRecord();
        r.setCustomerId(customerId);
        r.setChannel(channel);
        r.setDirection(direction);
        r.setContent(content);
        recordRepo.save(r);
    }
}
