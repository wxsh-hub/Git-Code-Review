package com.devops.ai.core.review.ai;

import com.devops.ai.core.crg.CrgClient;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

    /** evidence 上下文扩展行数（扩大到 30 行以覆盖类级 JavaDoc 和注解） */
    private static final int CONTEXT_MARGIN = 30;

    private static final String SYSTEM_PROMPT =
            "你是一个资深的代码审查专家。请独立判断以下代码是否存在真实缺陷，" +
            "并输出严格的 JSON 格式评估结果。\n\n" +
            "## 判断标准\n" +
            "1. 区分「代码缺陷」和「代码风格/设计偏好」——风格问题不是缺陷\n" +
            "2. 区分「代码缺陷」和「需求变更后的代码」——被修改的代码不一定是 bug\n" +
            "3. 判断严重度时从风险角度考虑：是否会导致线上故障、数据丢失或安全漏洞\n" +
            "4. diff 模式只能看到变更部分，未变更代码不可见 —— 不要因为「没看到定义」就判定不存在\n" +
            "5. 注释/JavaDoc 已声明原因（类级或方法级）→ 开发者已考虑过此问题，不是缺陷\n\n" +
            "## grep 查看补充上下文（最多使用一次）\n" +
            "如果代码片段信息不足、无法做出可靠判断，你**必须**在 JSON 中附带一个 grep 请求来查看关联代码，" +
            "而不是直接放弃或返回空。grep 会返回方法的完整实现（含注解和 JavaDoc）。\n" +
            "格式：在 JSON 中增加 \"grep\": { \"symbol\": \"类名.方法名\", \"file\": \"相对路径（可选）\", \"reason\": \"为什么需要查看\" }\n" +
            "示例：需要确认类上是否有 @Transactional 注解 → \"grep\": { \"symbol\": \"DataSyncServiceImpl.syncFull\", \"reason\": \"查看类级 JavaDoc 和注解\" }\n" +
            "系统收到 grep 后会执行并再次调用你，届时必须输出最终评估结果，不得再次请求 grep。\n\n" +
            "## 输出格式\n" +
            "输出必须是合法 JSON，不要包含 markdown 代码块标记：\n" +
            "{\n" +
            "  \"confidence\": 0.82,\n" +
            "  \"severity\": \"P1\",\n" +
            "  \"category\": \"NPE\",\n" +
            "  \"reason\": \"findById 返回 null 后未做检查直接调用 getName\",\n" +
            "  \"trigger\": \"当传入的 id 在数据库中不存在时\",\n" +
            "  \"suggestedFix\": \"用 Optional.ofNullable(user).orElseThrow(...) 包装\"\n" +
            "}\n" +
            "如需 grep，在以上 JSON 中增加 \"grep\" 字段即可。\n\n" +
            "## ⛔ 强制规则：即使证据不足也必须输出 JSON\n" +
            "- 无论信息是否充分，**必须**输出上述格式的 JSON 对象，不得返回空响应\n" +
            "- 证据充分 → confidence 按实际情况打分（0.85+ 确证）\n" +
            "- 证据不足且无法 grep → confidence ≤ 0.70，在 reason 中说明不确定性\n" +
            "- 证据不足但可以通过 grep 查 → 先发 grep 请求，拿到结果后再出最终评估\n\n" +
            "## confidence 评分规则\n" +
            "- 使用精确的小数值（如 0.87、0.63、0.91），不要四舍五入到 0.05 的整数倍\n" +
            "- 这个分数代表你对该问题「确实是缺陷」的把握程度\n" +
            "- 0.85+ = 几乎确定是缺陷（有明确代码证据，代码上下文中就能证实）\n" +
            "- 0.70-0.84 = 有缺陷趋势/特征，但从代码上下文无法完全证实\n" +
            "- 0.50-0.69 = 可能是缺陷或设计不佳（需要较多推测、有不确定性）\n" +
            "- 0.30-0.49 = 大概率不是缺陷（纯猜测/不确定/「可能有风险」/「建议检查」→ 给这个分数）\n" +
            "- **如果你在 reason 中写了「可能」「也许」「不确定」「需要验证」「建议检查」「不清楚」，confidence 必须 ≤ 0.70**\n" +
            "- **猜测性判断（含「若」「可能」「假设」「建议检查」且无确凿代码证据）→ confidence ≤ 0.60，不要硬报**";

    private final LlmClient llmClient;
    private final AiConfigRepository aiConfigRepository;
    private final ConfigEncryptor configEncryptor;

    /** Phase 5 — CRG 客户端（可选注入，CRG 不可用时为 null） */
    @Autowired(required = false)
    private CrgClient crgClient;

    /** 1.2 — GrepTracer，复用其符号解析能力，避免重复实现 */
    @Autowired(required = false)
    private GrepTracer grepTracer;

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

        String repoPath = context.getRepoPath();

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
                    String response = callReviewLlm(provider, apiKey, apiUrl, model, f, repoPath);
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
                                 String model, Finding f, String repoPath) {
        String userPrompt = buildUserPrompt(f, repoPath);
        log.debug("Calling review LLM for finding {} ({} lines {}-{})",
                f.getId(), f.getFile(), f.getStartLine(), f.getEndLine());

        // 第一轮：LLM 可能输出最终 JSON，也可能请求 grep
        String firstResponse = llmClient.call(provider, apiKey, apiUrl, model,
                SYSTEM_PROMPT, userPrompt, 1024, 0.0, "json_object");

        // 检查是否包含 grep 请求
        GrepRequest grepReq = extractGrepRequest(firstResponse);
        if (grepReq == null) {
            return firstResponse;
        }

        // 执行 grep 获取补充上下文
        log.info("Review LLM requested grep for finding {}: symbol={}, reason={}",
                f.getId(), grepReq.symbol, grepReq.reason);
        String grepResult = executeGrepForReview(grepReq, repoPath);

        // 第二轮：追加 grep 结果，要求输出最终评估
        StringBuilder followUp = new StringBuilder(userPrompt);
        followUp.append("\n\n--- grep 结果（你请求的补充上下文）---\n");
        followUp.append(grepResult);
        followUp.append("\n\n请基于以上 grep 结果和原始代码上下文，输出最终的 JSON 评估。");
        followUp.append("不得再次请求 grep。");

        return llmClient.call(provider, apiKey, apiUrl, model,
                SYSTEM_PROMPT, followUp.toString(), 1024, 0.0, "json_object");
    }

    /**
     * 从 LLM 响应中提取 grep 请求（如果有）。
     *
     * @param response LLM 返回的 JSON 字符串
     * @return GrepRequest，无 grep 请求时返回 null
     */
    private GrepRequest extractGrepRequest(String response) {
        if (response == null || response.trim().isEmpty()) return null;
        try {
            String json = extractJson(response);
            if (json.isEmpty()) return null;
            JsonNode root = MAPPER.readTree(json);
            if (root.has("grep") && !root.get("grep").isNull()) {
                JsonNode grepNode = root.get("grep");
                String symbol = grepNode.has("symbol") ? grepNode.get("symbol").asText() : null;
                if (symbol == null || symbol.trim().isEmpty()) return null;
                GrepRequest req = new GrepRequest();
                req.symbol = symbol.trim();
                req.file = grepNode.has("file") ? grepNode.get("file").asText() : null;
                req.reason = grepNode.has("reason") ? grepNode.get("reason").asText() : null;
                return req;
            }
        } catch (Exception e) {
            // JSON 解析失败说明不是 grep 请求，返回 null 走正常的解析流程
        }
        return null;
    }

    /**
     * 执行 review LLM 请求的 grep 查询，复用 GrepTracer。
     *
     * @param req      grep 请求
     * @param repoPath 仓库根路径
     * @return grep 结果文本，失败时返回错误说明
     */
    private String executeGrepForReview(GrepRequest req, String repoPath) {
        if (grepTracer == null) {
            return "(grep 不可用：GrepTracer 未注入)";
        }
        try {
            String result = grepTracer.search(repoPath, req.symbol, req.file);
            if (result == null || result.isEmpty()) {
                return "(grep 未找到结果: " + req.symbol + ")";
            }
            return result;
        } catch (Exception e) {
            log.warn("Review LLM grep failed for {}: {}", req.symbol, e.getMessage());
            return "(grep 失败: " + e.getMessage() + ")";
        }
    }

    /**
     * 构建 review LLM 的用户提示词。
     * evidence 优先从源文件扩展到上下各 CONTEXT_MARGIN 行，提供更完整的上下文。
     */
    private String buildUserPrompt(Finding f, String repoPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("请判断以下代码是否存在问题：\n\n");

        sb.append("文件: ").append(f.getFile() != null ? f.getFile() : "unknown").append("\n");
        sb.append("行号: ").append(f.getStartLine()).append("-").append(f.getEndLine()).append("\n\n");

        // 尝试从源文件扩展 evidence 上下文
        String expandedEvidence = expandEvidence(f, repoPath);
        sb.append("代码上下文:\n```\n");
        sb.append(expandedEvidence).append("\n```\n\n");

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

        // Phase 5: CRG 调用链上下文
        if (f.getSymbol() != null && !f.getSymbol().isEmpty()) {
            String crgContext = buildCrgCallChainContext(f.getSymbol(), repoPath);
            if (crgContext != null) {
                sb.append("\n").append(crgContext);
            }
        }

        sb.append("\n请输出你的独立评估（JSON 格式）：");
        return sb.toString();
    }

    /**
     * 从源文件读取 evidence 上下文：[startLine - MARGIN, endLine + MARGIN]。
     * 读取失败时回退到原始 evidence。
     */
    private String expandEvidence(Finding f, String repoPath) {
        if (repoPath == null || f.getFile() == null || f.getStartLine() <= 0) {
            return f.getEvidence() != null ? f.getEvidence() : "(无)";
        }

        File sourceFile = new File(repoPath, f.getFile());
        if (!sourceFile.exists() || !sourceFile.isFile()) {
            return f.getEvidence() != null ? f.getEvidence() : "(无)";
        }

        int startLine = Math.max(1, f.getStartLine() - CONTEXT_MARGIN);
        int endLine = f.getEndLine() + CONTEXT_MARGIN;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(sourceFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (lineNum < startLine) continue;
                if (lineNum > endLine) break;
                sb.append(String.format("%4d | ", lineNum)).append(line).append("\n");
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        } catch (Exception e) {
            log.debug("Failed to read source for evidence expansion: {}: {}",
                    sourceFile.getAbsolutePath(), e.getMessage());
        }

        return f.getEvidence() != null ? f.getEvidence() : "(无)";
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
    // Phase 5: CRG 调用链上下文
    // ================================================================

    /**
     * 为 review LLM 构建 CRG 调用链上下文，帮助做出更准确的置信度判断。
     *
     * <p>优先走 GrepTracer（返回调用者 + 被调用者 + 方法体，与主审查 LLM 一致），
     * 不可用时 fallback 到直接 CRG 查询（仅名字）。</p>
     *
     * @param symbol   方法符号（如 UserService.save）
     * @param repoPath 仓库根路径
     * @return 格式化的调用链文本，CRG 不可用或查询失败时返回 null
     */
    private String buildCrgCallChainContext(String symbol, String repoPath) {
        try {
            // 优先走 GrepTracer：返回完整调用链 + 方法体（与主审查 LLM 信息量一致）
            if (grepTracer != null) {
                String result = grepTracer.search(repoPath, symbol, null);
                if (result != null && !result.isEmpty()
                        && !result.startsWith("(未找到") && !result.startsWith("(grep")
                        && !result.startsWith("(文件不存在") && !result.startsWith("(读取文件失败")) {
                    return "调用链分析（CRG）:\n" + result;
                }
                log.debug("[CRG Review Context] GrepTracer returned no useful result for '{}', "
                        + "falling back to direct CRG query", symbol);
            }

            // fallback: GrepTracer 不可用或查询失败时，走原有 CRG 直接查询逻辑（仅名字）
            if (crgClient == null || !crgClient.isEnabled()) return null;

            String resolved = null;
            String methodName = symbol.contains(".")
                    ? symbol.substring(symbol.lastIndexOf('.') + 1)
                    : symbol;
            java.util.List<com.devops.ai.core.crg.CrgModels.CrgNode> candidates =
                    crgClient.searchNodes(methodName, "Function", 10);
            if (candidates != null && !candidates.isEmpty()) {
                if (symbol.contains(".")) {
                    String className = symbol.substring(0, symbol.lastIndexOf('.'));
                    String needle = className + "." + methodName;
                    for (com.devops.ai.core.crg.CrgModels.CrgNode node : candidates) {
                        String qn = node.getQualifiedName();
                        if (qn != null && qn.endsWith(needle)) {
                            resolved = qn;
                            break;
                        }
                    }
                }
                if (resolved == null) {
                    resolved = candidates.get(0).getQualifiedName();
                }
            }
            if (resolved == null) return null;

            StringBuilder sb = new StringBuilder();
            sb.append("调用链分析（CRG）:\n");

            // callers
            com.devops.ai.core.crg.CrgModels.CrgQueryResult callers =
                    crgClient.queryGraph("callers_of", resolved);
            if (callers != null && callers.hasResults()) {
                int count = callers.getResults().size();
                sb.append("- 被 ").append(count).append(" 处调用: ");
                int shown = 0;
                for (com.devops.ai.core.crg.CrgModels.CrgNode n : callers.getResults()) {
                    if (shown >= 3) { sb.append("..."); break; }
                    sb.append(n.getName() != null ? n.getName() : (n.getQualifiedName() != null
                            ? n.getQualifiedName().split("::").length > 1
                                ? n.getQualifiedName().split("::")[1] : n.getQualifiedName() : "?"));
                    if (++shown < Math.min(count, 3)) sb.append(", ");
                }
                sb.append("\n");
            } else {
                sb.append("- 无调用者\n");
            }

            // callees
            com.devops.ai.core.crg.CrgModels.CrgQueryResult callees =
                    crgClient.queryGraph("callees_of", resolved);
            if (callees != null && callees.hasResults()) {
                int count = callees.getResults().size();
                sb.append("- 调用了 ").append(count).append(" 个方法: ");
                int shown = 0;
                for (com.devops.ai.core.crg.CrgModels.CrgNode n : callees.getResults()) {
                    if (shown >= 3) { sb.append("..."); break; }
                    sb.append(n.getName() != null ? n.getName() : (n.getQualifiedName() != null
                            ? n.getQualifiedName().split("::").length > 1
                                ? n.getQualifiedName().split("::")[1] : n.getQualifiedName() : "?"));
                    if (++shown < Math.min(count, 3)) sb.append(", ");
                }
                sb.append("\n");
            } else {
                sb.append("- 无被调用者\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.debug("[CRG Review Context] call chain context build failed for '{}': {}", symbol, e.getMessage());
            return null;
        }
    }

    // ================================================================
    // 配置读取
    // ================================================================

    /**
     * 获取 review LLM 的 provider，未配置时回退到主 LLM provider。
     */
    private String getReviewProvider() {
        String provider = readConfig("ai.review.provider");
        return (provider != null && !provider.isEmpty()) ? provider : "deepseek";
    }

    /**
     * 获取 review LLM 的 model，未配置时回退到 deepseek-v4-pro。
     */
    private String getReviewModel() {
        String model = readConfig("ai.review.model");
        return (model != null && !model.isEmpty()) ? model : "deepseek-v4-pro";
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

    /** review LLM 的 grep 请求 */
    static class GrepRequest {
        String symbol;   // 类名.方法名（如 DataSyncServiceImpl.syncFull）
        String file;     // 相对路径（可选）
        String reason;   // 为什么需要 grep
    }
}
