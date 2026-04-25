package com.example.demo.service;

import com.example.demo.entity.Customer;
import com.example.demo.repository.CustomerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * 网站分析服务 — 从客户官网自动提取信息并生成客户画像
 * <p>
 * 流程：
 * <ol>
 *   <li>Jsoup 爬取目标网站首页 + About页 + Contact页</li>
 *   <li>提取关键文本内容（标题、描述、导航分类、公司介绍等）</li>
 *   <li>调用 LLM 分析提取的内容，生成结构化客户画像</li>
 *   <li>自动创建 Customer 记录并保存到数据库</li>
 * </ol>
 */
@Service
public class WebsiteAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(WebsiteAnalysisService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Jsoup 请求超时（毫秒） */
    private static final int FETCH_TIMEOUT_MS = 15_000;
    /** 每个页面提取的最大文本长度 */
    private static final int MAX_TEXT_PER_PAGE = 3000;
    /** 要尝试爬取的子页面路径 */
    private static final List<String> SUB_PATHS = List.of(
            "/about", "/about/", "/about-us", "/about-us/",
            "/contact", "/contact/", "/contact-us",
            "/products", "/categories", "/ranges"
    );

    private final ChatModel chatModel;
    private final CustomerRepository customerRepo;

    public WebsiteAnalysisService(ChatModel chatModel,
                                  CustomerRepository customerRepo) {
        this.chatModel = chatModel;
        this.customerRepo = customerRepo;
    }

    // ── 结果 Record ─────────────────────────────────────────────────────────

    public record WebsiteAnalysisResult(
            Long customerId,                 // 新创建的客户ID
            Map<String, Object> profile,     // 结构化客户画像
            String rawLlmResponse,           // LLM 原始响应
            int pagesCrawled,                // 成功爬取的页面数
            long crawlMs,                    // 爬取耗时
            long llmMs                       // LLM 分析耗时
    ) {}

    // ── 主方法 ─────────────────────────────────────────────────────────────

    /**
     * 分析指定 URL 的客户网站，生成画像并存入数据库。
     *
     * @param url 客户网站地址，如 https://www.leonardo.co.uk/
     * @return 分析结果
     */
    public WebsiteAnalysisResult analyzeWebsite(String url) {
        // 标准化 URL
        String baseUrl = normalizeUrl(url);
        log.info("website.analysis start url={}", baseUrl);

        // ── 第一步：爬取网站内容 ─────────────────────────────────────────
        long crawlStart = System.nanoTime();
        Map<String, String> pageContents = crawlWebsite(baseUrl);
        long crawlMs = Duration.ofNanos(System.nanoTime() - crawlStart).toMillis();

        if (pageContents.isEmpty()) {
            throw new RuntimeException("无法访问目标网站: " + baseUrl);
        }

        log.info("website.analysis crawl done url={} pages={} crawlMs={}",
                baseUrl, pageContents.size(), crawlMs);

        // ── 第二步：构建 Prompt，调用 LLM 分析 ───────────────────────────
        String prompt = buildAnalysisPrompt(baseUrl, pageContents);

        long llmStart = System.nanoTime();
        String llmResponse = chatModel.chat(prompt);
        long llmMs = Duration.ofNanos(System.nanoTime() - llmStart).toMillis();

        log.info("website.analysis llm done url={} llmMs={} responseLen={}",
                baseUrl, llmMs, llmResponse.length());

        // ── 第三步：解析 LLM 响应 ───────────────────────────────────────
        Map<String, Object> profile = parseLlmResponse(llmResponse);

        // ── 第四步：创建 Customer 并保存到数据库 ─────────────────────────
        Customer customer = buildCustomerFromProfile(profile, baseUrl);
        customerRepo.save(customer);

        log.info("website.analysis saved customerId={} companyName='{}' grade={}",
                customer.getId(), customer.getCompanyName(), customer.getGrade());

        return new WebsiteAnalysisResult(
                customer.getId(), profile, llmResponse,
                pageContents.size(), crawlMs, llmMs);
    }

    // ── 爬虫逻辑 ─────────────────────────────────────────────────────────

    /**
     * 爬取网站首页及常见子页面，返回 页面URL → 提取文本 的映射。
     */
    private Map<String, String> crawlWebsite(String baseUrl) {
        Map<String, String> results = new LinkedHashMap<>();

        // 首页（必须成功）
        String homepageText = fetchPage(baseUrl);
        if (homepageText != null && !homepageText.isBlank()) {
            results.put(baseUrl, homepageText);
        }

        // 尝试常见子页面
        for (String path : SUB_PATHS) {
            if (results.size() >= 5) break;  // 限制最多5个页面，避免过度爬取

            String subUrl = baseUrl.replaceAll("/+$", "") + path;
            try {
                String text = fetchPage(subUrl);
                if (text != null && !text.isBlank() && text.length() > 100) {
                    results.put(subUrl, text);
                }
            } catch (Exception e) {
                // 子页面爬取失败不影响主流程
                log.debug("website.analysis sub-page skip url={} err={}", subUrl, e.getMessage());
            }
        }

        return results;
    }

    /**
     * 用 Jsoup 爬取单个页面，提取有意义的文本内容。
     */
    private String fetchPage(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; TradeAIBot/1.0)")
                    .timeout(FETCH_TIMEOUT_MS)
                    .followRedirects(true)
                    .get();

            StringBuilder text = new StringBuilder();

            // 提取 title
            String title = doc.title();
            if (title != null && !title.isBlank()) {
                text.append("页面标题: ").append(title.trim()).append("\n");
            }

            // 提取 meta description
            Element metaDesc = doc.selectFirst("meta[name=description]");
            if (metaDesc != null) {
                String desc = metaDesc.attr("content");
                if (!desc.isBlank()) {
                    text.append("Meta描述: ").append(desc.trim()).append("\n");
                }
            }

            // 提取导航菜单（品类信息）
            Elements navLinks = doc.select("nav a, .nav a, .menu a, #menu a");
            if (!navLinks.isEmpty()) {
                Set<String> navTexts = new LinkedHashSet<>();
                for (Element a : navLinks) {
                    String linkText = a.text().trim();
                    if (!linkText.isBlank() && linkText.length() < 50) {
                        navTexts.add(linkText);
                    }
                }
                if (!navTexts.isEmpty()) {
                    text.append("导航/分类: ").append(String.join(", ", navTexts)).append("\n");
                }
            }

            // 提取 h1-h3 标题
            Elements headings = doc.select("h1, h2, h3");
            Set<String> headingTexts = new LinkedHashSet<>();
            for (Element h : headings) {
                String ht = h.text().trim();
                if (!ht.isBlank() && ht.length() < 100) {
                    headingTexts.add(ht);
                }
            }
            if (!headingTexts.isEmpty()) {
                text.append("主要标题: ").append(String.join(" | ", headingTexts)).append("\n");
            }

            // 提取正文段落（p标签）
            Elements paragraphs = doc.select("p");
            for (Element p : paragraphs) {
                if (text.length() >= MAX_TEXT_PER_PAGE) break;
                String pt = p.text().trim();
                if (pt.length() > 30) {  // 过滤过短的无意义段落
                    text.append(pt).append("\n");
                }
            }

            // 提取联系信息区域
            Elements contactElements = doc.select(
                    "[class*=contact], [class*=address], [class*=footer], address");
            for (Element ce : contactElements) {
                if (text.length() >= MAX_TEXT_PER_PAGE) break;
                String ct = ce.text().trim();
                if (ct.length() > 20 && ct.length() < 500) {
                    text.append("联系区域: ").append(ct).append("\n");
                }
            }

            String result = text.toString().trim();
            // 截断到最大长度
            if (result.length() > MAX_TEXT_PER_PAGE) {
                result = result.substring(0, MAX_TEXT_PER_PAGE);
            }
            return result;

        } catch (Exception e) {
            log.warn("website.analysis fetch failed url={} err={}", url, e.getMessage());
            return null;
        }
    }

    // ── Prompt 构建 ─────────────────────────────────────────────────────────

    private String buildAnalysisPrompt(String baseUrl, Map<String, String> pageContents) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一位资深外贸市场分析顾问。我们是一家中国外贸公司，主营家居用品、礼品和日用消费品的出口。
                现在需要你分析一个潜在客户的网站，生成详细的客户画像，帮助销售团队判断是否值得跟进以及如何切入。
                
                ## 你需要从网站内容中分析以下信息
                
                请严格按 JSON 格式输出，不要输出其他内容：
                ```json
                {
                  "companyName": "公司名称",
                  "country": "所在国家",
                  "industry": "行业领域",
                  "companySize": "企业规模推断（如：中型批发商、大型连锁零售等）",
                  "businessModel": "商业模式（如：B2B批发、B2C零售、进口分销等）",
                  "productCategories": ["经营的产品品类列表"],
                  "targetMarket": "他们的目标市场/客户群",
                  "companyHistory": "公司历史和背景",
                  "strengths": ["公司优势或特点"],
                  "potentialNeeds": ["基于分析推断的潜在采购需求"],
                  "matchingProducts": ["我方可匹配的产品线建议"],
                  "approachStrategy": "推荐的切入策略",
                  "riskFactors": ["合作风险或需注意事项"],
                  "grade": "A/B/C/D",
                  "gradeReason": "分级理由",
                  "contactInfo": "从网站提取的联系方式（如有）",
                  "profileSummary": "200字以内的客户画像总结"
                }
                ```
                
                ## 分级标准
                - **A级**：产品高度匹配 + 有明确进口需求 + 企业实力强 + 切入时机好
                - **B级**：产品有一定匹配 + 可能有需求 + 需进一步验证
                - **C级**：匹配度不确定 + 信息不足 + 需更多调研
                - **D级**：产品明显不匹配 / 非目标行业 / 无进口需求
                
                ## 我方公司产品线（供匹配参考）
                保温杯、户外水杯、杯垫（陶瓷/软木/硅胶）、马克杯、餐垫、
                厨房用品、硅胶制品、宠物用品、户外露营用品、定制礼品、
                收纳用品、家居装饰品
                
                ## 网站地址
                """);
        sb.append(baseUrl).append("\n\n");

        sb.append("## 爬取到的网站内容\n\n");
        for (Map.Entry<String, String> entry : pageContents.entrySet()) {
            sb.append("### 页面: ").append(entry.getKey()).append("\n");
            sb.append(entry.getValue()).append("\n\n");
        }

        return sb.toString();
    }

    // ── 响应解析 ─────────────────────────────────────────────────────────────

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
            log.warn("website.analysis json parse failed err={}", e.getMessage());
        }
        // 解析失败时的降级处理
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("profileSummary", response);
        fallback.put("grade", "C");
        fallback.put("gradeReason", "LLM响应解析失败，默认C级待人工审核");
        return fallback;
    }

    // ── 构建 Customer 实体 ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Customer buildCustomerFromProfile(Map<String, Object> profile, String websiteUrl) {
        Customer c = new Customer();

        c.setCompanyName(getStr(profile, "companyName", "未知公司"));
        c.setCountry(getStr(profile, "country", "未知"));
        c.setIndustry(getStr(profile, "industry", "未知"));
        c.setCompanySize(getStr(profile, "companySize", "未知"));
        c.setSource("website_analysis");

        // 产品品类 → productInterest
        Object categories = profile.get("productCategories");
        if (categories instanceof List) {
            c.setProductInterest(String.join(", ", (List<String>) categories));
        } else {
            c.setProductInterest(getStr(profile, "productCategories", ""));
        }

        // AI 画像全文
        c.setAiProfile(getStr(profile, "profileSummary", ""));

        // 分级
        String grade = getStr(profile, "grade", "C").toUpperCase().trim();
        if (!grade.matches("[ABCD]")) grade = "C";
        c.setGrade(grade);
        c.setGradeReason(getStr(profile, "gradeReason", ""));

        // 跟进建议 = 切入策略
        c.setFollowUpSuggestion(getStr(profile, "approachStrategy", ""));

        // 联系人信息（如果能从网站提取到邮箱）
        String contactInfo = getStr(profile, "contactInfo", "");
        if (contactInfo.contains("@")) {
            c.setEmail(contactInfo);
        }

        return c;
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://" + trimmed;
        }
        return trimmed;
    }

    private static String getStr(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        if (v == null) return defaultValue;
        String s = v.toString().trim();
        return s.isEmpty() ? defaultValue : s;
    }
}
