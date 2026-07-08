package com.devops.ai.core.review.ai;

import com.devops.ai.core.llm.LlmClient;
import com.devops.ai.core.review.model.*;
import com.devops.ai.infrastructure.entity.AiConfig;
import com.devops.ai.infrastructure.repository.AiConfigRepository;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 双 LLM 交叉验证服务 — Phase 6 核心。
 *
 * <p>对每条 P0/P1 Finding 调用独立的 review LLM，结构化输出
 * severity + category + confidence + reason + trigger + suggestedFix，
 * 取两个 LLM 置信度的平均值决定最终状态。</p>
 *
 * <h3>配置回退规则</h3>
 * <ol>
 *   <li>优先使用 ai_config 中的 {@code ai.review.provider} / {@code ai.review.model}</li>
 *   <li>未配置时回退到主 LLM 配置（{@code llm.provider} / {@code llm.modelName}）</li>
 *   <li>API Key 和 URL 始终使用主 LLM 配置</li>
 * </ol>
 *
 * <h3>状态判定</h3>
 * <ul>
 *   <li>confidence = (原始置信度 + review 置信度) / 2</li>
 *   <li>confidence ≥ 0.7 → CONFIRMED，进入正式统计</li>
 *   <li>confidence < 0.7 → FALSE_POSITIVE，不进统计</li>
 * </ul>
 */
@Component
public class ReviewLlmService {

    private static final Logger log = LoggerFactory.getLogger(ReviewLlmService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true);

    /** 并发线程数 */
    private static final int CONCURRENCY = 8;

    private static final String SYSTEM_PROMPT =
            "你是一个资深的代码审查专家。请独立判断以下代码是否存在真实缺陷，" +
            "并输出严格的 JSON 格式评估结果。\n\n" +
            "判断标准：\n" +
            "1. 区分「代码缺陷」和「代码风格/设计偏好」——风格问题不是缺陷\n" +
            "2. 区分「代码缺陷」和「需求变更后的代码」——被修改的代码不一定是 bug\n" +
            "3. 判断严重度时从风险角度考虑：是否会导致线上故障、数据丢失或安全漏洞\n" +
            "4. diff 模式只能看到变更部分，未变更代码不可见 —— 不要因为「没看到定义」就判定不存在\n\n" +
            "输出必须是合法 JSON，不要包含 markdown 代码块标记：\n" +
            "{\n" +
            "  \"confidence\": 0.82,\n" +
            "  \"severity\": \"P1\",\n" +
            "  \"category\": \"NPE\",\n" +
            "  \"reason\": \"findById 返回 null 后未做检查直接调用 getName\",\n" +
            "  \"trigger\": \"当传入的 id 在数据库中不存在时\",\n" +
            "  \"suggestedFix\": \"用 Optional.ofNullable(user).orElseThrow(...) 包装\"\n" +
            "}\n\n" +
            "confidence 评分规则：\n" +
            "- 使用精确的小数值（如 0.87、0.63、0.91），不要四舍五入到 0.05 的整数倍\n" +
            "- 这个分数代表你对该问题「确实是缺陷」的把握程度\n" +
            "- 0.85+ = 几乎确定是缺陷（有明确代码证据，diff 中就能证实）\n" +
            "- 0.70-0.84 = 有缺陷趋势/特征，但从 diff 无法完全证实（如代码模式可疑但缺少上下文）\n" +
            "- 0.50-0.69 = 可能是缺陷或设计不佳（需要较多推测、有不确定性）\n" +
            "- 0.30-0.49 = 大概率不是缺陷（纯猜测/不确定/「可能有风险」/「建议检查」→ 给这个分数）\n" +
            "- **如果你在 reason 中写了「可能」「也许」「不确定」「需要验证」「建议检查」「不清楚」，confidence 必须 ≤ 0.70**";

    private final LlmClient llmClient;
    private final AiConfigRepository aiConfigRepository;
    private final ConfigEncryptor configEncryptor;

    public ReviewLlmService(LlmClient llmClient, AiConfigRepository aiConfigRepository,
                            ConfigEncryptor configEncryptor) {
        this.llmClient = llmClient;
        this.aiConfigRepository = aiConfigRepository;
        this.configEncryptor = configEncryptor;
    }

    // ================================================================
    // 主入口
    // ================================================================

    /**
     * 对 P0/P1/P2 Finding 执行双 LLM 交叉验证。
     *
     * <p>P3-P4 Finding 直接透传，不做验证以节省 API 调用。</p>
     *
     * @param findings 原始 Finding 列表（已完成 blame 追溯）
     * @param context  审查上下文
     * @return 更新了 confidence/status/severity/category/reviewer 的 Finding 列表
     */
    public List<Finding> crossValidate(List<Finding> findings, CodeReviewContext context) {
        if (findings == null || findings.isEmpty()) return Collections.emptyList();

        // 检查 review LLM 配置是否可用
        String provider = getReviewProvider();
        String model = getReviewModel();
        String apiKey = getApiKey();
        String apiUrl = getApiUrl(provider);

        if (apiKey.isEmpty()) {
            log.warn("API key not configured, skipping review LLM cross-validation");
            return findings;
        }

        // 只取 P0/P1/P2
        List<Finding> high = new ArrayList<>();
        for (Finding f : findings) {
            if (isHighSeverity(f.getSeverity())) high.add(f);
        }
        if (high.isEmpty()) return findings;

        int total = high.size();
        AtomicInteger reviewed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        List<Future<?>> futures = new ArrayList<>(total);
        long startTime = System.currentTimeMillis();

        for (Finding f : high) {
            futures.add(executor.submit(() -> {
                try {
                    String response = callReviewLlm(provider, apiKey, apiUrl, model, f);
                    ReviewOutput output = parseReviewResponse(response);
                    if (output != null) {
                        synchronized (f) {
                            applyReview(f, output);
                        }
                        reviewed.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.warn("Review LLM failed for {} ({} lines {}-{}): {}",
                            f.getId(), f.getFile(), f.getStartLine(), f.getEndLine(), e.getMessage());
                    failed.incrementAndGet();
                } finally {
                    int done = completed.incrementAndGet();
                    if (done % 20 == 0 || done == total) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        log.info("Review LLM progress: {}/{} ({} OK, {} failed) in {}s",
                                done, total, reviewed.get(), failed.get(), elapsed / 1000);
                    }
                }
            }));
        }

        // 等待全部完成
        for (Future<?> fut : futures) {
            try { fut.get(); } catch (Exception ignored) { }
        }
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Review LLM complete: {} reviewed, {} failed, {} total ({}s, {} threads)",
                reviewed.get(), failed.get(), total, elapsed / 1000, CONCURRENCY);
        return findings;
    }

    // ================================================================
    // LLM 调用
    // ================================================================

    private String callReviewLlm(String provider, String apiKey, String apiUrl,
                                 String model, Finding f) {
        String userPrompt = buildUserPrompt(f);
        log.debug("Calling review LLM for finding {} ({} lines {}-{})",
                f.getId(), f.getFile(), f.getStartLine(), f.getEndLine());
        return llmClient.call(provider, apiKey, apiUrl, model,
                SYSTEM_PROMPT, userPrompt, 1024, 0.0, "json_object");
    }

    /**
     * 构建 review LLM 的用户提示词。
     */
    private String buildUserPrompt(Finding f) {
        StringBuilder sb = new StringBuilder();
        sb.append("请判断以下代码是否存在问题：\n\n");

        sb.append("文件: ").append(f.getFile() != null ? f.getFile() : "unknown").append("\n");
        sb.append("行号: ").append(f.getStartLine()).append("-").append(f.getEndLine()).append("\n\n");

        sb.append("证据代码:\n```\n");
        sb.append(f.getEvidence() != null ? f.getEvidence() : "(无)").append("\n```\n\n");

        sb.append("初步判定:\n");
        sb.append("- 严重度: ").append(formatSeverity(f.getSeverity())).append("\n");
        sb.append("- 分类: ").append(formatCategory(f.getCategory())).append("\n");
        sb.append("- 置信度: ").append(String.format("%.0f%%", f.getConfidence() * 100)).append("\n");

        if (f.getTrigger() != null && !f.getTrigger().isEmpty()) {
            sb.append("- 触发条件: ").append(f.getTrigger()).append("\n");
        }

        // blame 信息（Phase 5 产出）
        if (f.getOwner() != null && !f.getOwner().isEmpty()) {
            sb.append("\nBlame 信息:\n");
            sb.append("- 引入者: ").append(f.getOwner()).append("\n");
            if (f.getBlameCommitIds() != null && !f.getBlameCommitIds().isEmpty()) {
                sb.append("- 引入 commit: ").append(String.join(", ",
                        f.getBlameCommitIds().stream()
                                .map(id -> id.length() >= 8 ? id.substring(0, 8) : id)
                                .toArray(String[]::new))).append("\n");
            }
        }

        sb.append("\n请输出你的独立评估（JSON 格式）：");
        return sb.toString();
    }

    // ================================================================
    // 响应解析
    // ================================================================

    /**
     * 解析 review LLM 的 JSON 响应。
     * LLM 已开启 JSON 模式，输出应为合法 JSON。解析失败时修复字符串值中未转义字符后重试。
     */
    ReviewOutput parseReviewResponse(String response) {
        if (response == null || response.trim().isEmpty()) return null;

        String json = extractJson(response);

        // 直接解析（JSON 模式下成功率极高）
        ReviewOutput output = tryParse(json);
        if (output != null) return output;

        // 兜底：修复 JSON 字符串值中未转义字符后重试
        String repaired = repairJsonStringValues(json);
        if (!repaired.equals(json)) {
            output = tryParse(repaired);
            if (output != null) {
                log.debug("Review LLM JSON repaired successfully");
                return output;
            }
        }

        log.warn("Failed to parse review LLM JSON even after repair, raw={}",
                response.length() > 200 ? response.substring(0, 200) + "..." : response);
        return null;
    }

    private ReviewOutput tryParse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return buildOutput(node);
        } catch (Exception e) {
            return null;
        }
    }

    private ReviewOutput buildOutput(JsonNode node) {
        ReviewOutput output = new ReviewOutput();
        output.confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.5;
        output.severity = node.has("severity") ? node.get("severity").asText() : null;
        output.category = node.has("category") ? node.get("category").asText() : null;
        output.reason = node.has("reason") ? node.get("reason").asText() : null;
        output.trigger = node.has("trigger") ? node.get("trigger").asText() : null;
        output.suggestedFix = node.has("suggestedFix") ? node.get("suggestedFix").asText() : null;

        if (output.confidence < 0) output.confidence = 0;
        if (output.confidence > 1.0) output.confidence = 1.0;
        return output;
    }

    // ================================================================
    // JSON 容错工具方法（与 CodeReviewAiService 保持一致）
    // ================================================================

    /**
     * 从 LLM 响应中提取 JSON 部分。
     */
    static String extractJson(String response) {
        if (response == null || response.trim().isEmpty()) return "";
        String text = response.trim();

        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            int jsonStart = text.indexOf('\n', fenceStart);
            if (jsonStart >= 0) {
                int fenceEnd = text.indexOf("```", jsonStart + 1);
                if (fenceEnd >= 0) {
                    String inner = text.substring(jsonStart + 1, fenceEnd).trim();
                    if (inner.startsWith("{")) return inner;
                }
            }
        }

        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1);
        }
        return "";
    }

    /**
     * 修复 JSON 字符串值中常见的未转义字符。
     */
    static String repairJsonStringValues(String json) {
        if (json == null || json.isEmpty()) return json;

        StringBuilder sb = new StringBuilder(json.length() * 2);
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }

            if (inString) {
                if (c == '\\') {
                    sb.append(c);
                    escaped = true;
                } else if (c == '"') {
                    int next = nextNonWhitespace(json, i + 1);
                    if (next == ':' || next == ',' || next == '}' || next == ']' || next < 0) {
                        inString = false;
                        sb.append(c);
                    } else {
                        sb.append('\\').append(c);
                    }
                } else if (c == '\n' || c == '\r' || c == '\t') {
                    sb.append(c == '\n' ? "\\n" : c == '\r' ? "\\r" : "\\t");
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
                if (c == '"') inString = true;
            }
        }
        return sb.toString();
    }

    private static int nextNonWhitespace(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return c;
        }
        return -1;
    }

    /**
     * 将 review LLM 输出应用到 Finding。
     */
    private void applyReview(Finding f, ReviewOutput output) {
        double originalConfidence = f.getConfidence();
        double finalConfidence = (originalConfidence + output.confidence) / 2.0;

        // 代码侧兜底：review LLM 表达了不确定 → 强制压低置信度
        // 不管 LLM 给的 confidence 多高，reason 里有不确定词汇就说明它自己都没把握
        if (output.reason != null && containsUncertaintyMarkers(output.reason)) {
            finalConfidence = Math.min(finalConfidence, 0.70);
            log.debug("Confidence capped at {} due to uncertainty markers in reason: {}",
                    finalConfidence,
                    output.reason.length() > 80 ? output.reason.substring(0, 80) + "..." : output.reason);
        }

        f.setConfidence(finalConfidence);
        f.setStatus(FindingStatus.fromConfidence(finalConfidence));

        // 先用 review LLM 的分类覆盖关键词推断值
        // 再根据分类确定性映射严重级别 — 消除 LLM 偷懒给错 P 级的问题
        if (output.category != null) {
            FindingCategory cat = FindingCategory.fromCode(output.category);
            if (cat != FindingCategory.OTHER) {
                f.setCategory(cat);
                // 代码侧根据分类查表定级，不依赖 LLM 输出的 severity
                f.setSeverity(FindingSeverity.fromCategory(cat));
            }
        }

        // 兜底：始终用最终分类同步严重级别
        // （防止 review LLM 分类失败时，OCR 阶段基于关键词推断的严重级别残留）
        if (f.getCategory() != null && f.getCategory() != FindingCategory.OTHER) {
            f.setSeverity(FindingSeverity.fromCategory(f.getCategory()));
        }

        f.setReviewConclusion(output.reason);
        f.setReviewer("review LLM");

        // review LLM 的输出补全证据链
        if (output.trigger != null && !output.trigger.isEmpty()
                && (f.getTrigger() == null || f.getTrigger().isEmpty())) {
            f.setTrigger(output.trigger);
        }
        if (output.suggestedFix != null && !output.suggestedFix.isEmpty()
                && (f.getSuggestedFix() == null || f.getSuggestedFix().isEmpty())) {
            f.setSuggestedFix(output.suggestedFix);
        }

        log.debug("Finding {} cross-validated: confidence {}% → {}%, status={}",
                f.getId(),
                String.format("%.0f", originalConfidence * 100),
                String.format("%.0f", finalConfidence * 100),
                f.getStatus());
    }

    // ================================================================
    // 配置读取
    // ================================================================

    /**
     * 获取 review LLM 的 provider，未配置时回退到主 LLM provider。
     */
    private String getReviewProvider() {
        String provider = readConfig("ai.review.provider");
        return (provider != null && !provider.isEmpty()) ? provider : readConfig("llm.provider");
    }

    /**
     * 获取 review LLM 的 model，未配置时回退到主 LLM model。
     */
    private String getReviewModel() {
        String model = readConfig("ai.review.model");
        return (model != null && !model.isEmpty()) ? model : readConfig("llm.modelName");
    }

    private String getApiKey() {
        String encrypted = readConfig("llm.apiKey");
        if (encrypted == null || encrypted.isEmpty()) return "";
        try {
            return configEncryptor.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("Failed to decrypt API key: {}", e.getMessage());
            return encrypted;
        }
    }

    private String getApiUrl(String provider) {
        String customUrl = readConfig("llm.apiUrl");
        if (customUrl != null && !customUrl.trim().isEmpty()) return customUrl.trim();
        if (provider == null || provider.isEmpty()) return "https://api.deepseek.com";
        switch (provider) {
            case "deepseek": return "https://api.deepseek.com";
            case "openai": return "https://api.openai.com";
            case "anthropic": return "https://api.anthropic.com";
            default: return "https://api.deepseek.com";
        }
    }

    private String readConfig(String key) {
        AiConfig config = aiConfigRepository.findByConfigKey(key);
        return config != null ? config.getConfigValue() : "";
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private boolean isHighSeverity(FindingSeverity s) {
        return s == FindingSeverity.BLOCKER || s == FindingSeverity.HIGH || s == FindingSeverity.MEDIUM;
    }

    private String formatSeverity(FindingSeverity s) {
        if (s == null) return "未知";
        return s.getLevel() + " (" + s.getLabel() + ")";
    }

    private String formatCategory(FindingCategory c) {
        if (c == null) return "未知";
        return c.getLabel();
    }

    /** 检测 reason 中是否包含不确定表述（AI 自己都没把握） */
    static boolean containsUncertaintyMarkers(String text) {
        if (text == null || text.isEmpty()) return false;
        // 中文不确定表述
        return text.contains("可能") || text.contains("也许") || text.contains("不确定")
                || text.contains("需要验证") || text.contains("建议检查") || text.contains("不清楚")
                || text.contains("不明确") || text.contains("或在") || text.contains("不一定")
                || text.contains("需确认") || text.contains("怀疑") || text.contains("猜测")
                // 英文不确定表述
                || text.contains("might") || text.contains("may ") || text.contains("uncertain")
                || text.contains("unclear") || text.contains("possibly") || text.contains("not sure");
    }

    // ================================================================
    // 内部数据类
    // ================================================================

    /** review LLM 的结构化输出 */
    static class ReviewOutput {
        double confidence;
        String severity;   // "P0" / "P1" / ...
        String category;   // "NPE" / "SECURITY" / ...
        String reason;
        String trigger;
        String suggestedFix;
    }
}
