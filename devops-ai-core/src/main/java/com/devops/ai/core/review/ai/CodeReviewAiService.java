package com.devops.ai.core.review.ai;

import com.devops.ai.core.llm.LlmClient;
import com.devops.ai.core.review.model.*;
import com.devops.ai.core.review.parser.JavaFileParser;
import com.devops.ai.infrastructure.entity.AiConfig;
import com.devops.ai.infrastructure.exception.AiServiceException;
import com.devops.ai.infrastructure.repository.AiConfigRepository;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Component
public class CodeReviewAiService {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewAiService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true);

    private static final Map<String, String> DEFAULT_API_URLS = new LinkedHashMap<>();

    static {
        DEFAULT_API_URLS.put("deepseek", "https://api.deepseek.com");
        DEFAULT_API_URLS.put("openai", "https://api.openai.com");
        DEFAULT_API_URLS.put("anthropic", "https://api.anthropic.com");
    }

    private final LlmClient llmClient;
    private final AiConfigRepository aiConfigRepository;
    private final ConfigEncryptor configEncryptor;
    private final OcrmcpClient ocrmcpClient;
    private final FindingBlameTracer findingBlameTracer;
    private final ReviewLlmService reviewLlmService;
    private final SecretDetector secretDetector;
    private final FindingVerifier findingVerifier;

    @Value("${ocr.fallback-on-error:true}")
    private boolean ocrFallbackOnError;

    @Value("${ocr.scan-concurrency:4}")
    private int scanConcurrency;

    @Value("${ocr.scan-batch-size:20}")
    private int scanBatchSize;

    public CodeReviewAiService(LlmClient llmClient, AiConfigRepository aiConfigRepository,
                               ConfigEncryptor configEncryptor, OcrmcpClient ocrmcpClient,
                               FindingBlameTracer findingBlameTracer,
                               ReviewLlmService reviewLlmService,
                               SecretDetector secretDetector, FindingVerifier findingVerifier) {
        this.llmClient = llmClient;
        this.aiConfigRepository = aiConfigRepository;
        this.configEncryptor = configEncryptor;
        this.ocrmcpClient = ocrmcpClient;
        this.findingBlameTracer = findingBlameTracer;
        this.reviewLlmService = reviewLlmService;
        this.secretDetector = secretDetector;
        this.findingVerifier = findingVerifier;
    }

    // ================================================================
    // 主入口：优先 OCR，自动 fallback
    // ================================================================

    public CodeReviewResult review(CodeReviewContext context) {
        try {
            if (!ocrmcpClient.isAvailable()) {
                log.info("OCR not available, falling back to legacy review");
                return reviewLegacy(context);
            }
            return reviewWithOcr(context);
        } catch (Exception e) {
            log.error("OCR review failed, falling back to legacy: {}", e.getMessage());
            if (ocrFallbackOnError) {
                return reviewLegacy(context);
            }
            throw new AiServiceException("OCR review failed", e);
        }
    }

    // ================================================================
    // Phase 4: 审查流水线（三步）
    // ================================================================

    // --- 管线数据模型 ---

    /** 审查后处理管线接口（Phase 4 框架，后续 Phase 填充） */
    interface FindingPostProcessor {
        List<Finding> process(List<Finding> findings, CodeReviewContext context);
    }

    /** 业务链路分组模型 */
    static class ReviewGroup {
        String name;                    // 业务链路名，如 "user"
        List<FileDiff> files;          // 该组的文件列表
        String background;              // 审查背景上下文

        ReviewGroup(String name, List<FileDiff> files, String background) {
            this.name = name;
            this.files = files;
            this.background = background;
        }
    }

    // ================================================================
    // OCR 路径（三步流水线）
    // ================================================================

    private CodeReviewResult reviewWithOcr(CodeReviewContext context) throws Exception {
        List<FileDiff> diffs = context.getFileDiffs();

        // Step 1: 文件预筛选
        List<FileDiff> filtered = preFilter(diffs);

        // 模块计数（for buildResultFromFindings）
        Set<String> moduleSet = new LinkedHashSet<>();

        // Step 2+3: 调用 OCR MCP → 转换为 Finding
        List<Finding> allFindings = new ArrayList<>();

        if (context.getSinceHash() != null && context.getUntilHash() != null) {
            // diff 模式：按模块分组，每组一次 LLM 调用（打包该组所有 diff）。
            // 相比 OCR code_review_diff（逐文件 Plan+Main = 276 次 LLM 调用），
            // 此方式只需 5-7 次 LLM 调用，速度接近 legacy 但输出结构化 JSON，
            // 完整保留 blame → crossValidate → secret → verify 后处理管线。
            List<Finding> findings = reviewDiffByModule(filtered, context);
            for (Finding f : findings) {
                if (f.getModuleName() != null) moduleSet.add(f.getModuleName());
            }
            allFindings.addAll(findings);
            log.info("Diff review complete: {} findings from {} files", findings.size(), filtered.size());
        } else {
            // scan 模式（无 hash 范围）：仅深度扫描时走 OCR code_scan，分批 + 线程池并发
            if (context.isUseOcrDeepScan()) {
                List<List<FileDiff>> batches = splitIntoBatches(filtered, context, scanBatchSize);
                // 预先计算 graph background，避免每个 batch 内重复解析 JSON
                String graphBg = buildGraphBackground(context);
                ExecutorService executor = Executors.newFixedThreadPool(scanConcurrency);
                List<CompletableFuture<List<Finding>>> futures = new ArrayList<>();

                try {
                    for (int i = 0; i < batches.size(); i++) {
                        final int batchIdx = i;
                        final List<FileDiff> batch = batches.get(i);
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            try {
                                log.info("Batch {}/{}: {} files starting via code_scan",
                                        batchIdx + 1, batches.size(), batch.size());
                                List<Finding> batchFindings = reviewBatch(batch, context, graphBg, batchIdx);
                                log.info("Batch {}/{}: {} findings", batchIdx + 1, batches.size(), batchFindings.size());
                                return batchFindings;
                            } catch (Exception e) {
                                log.error("Batch {}/{} failed: {}", batchIdx + 1, batches.size(), e.getMessage());
                                return Collections.<Finding>emptyList();
                            }
                        }, executor));
                    }

                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                    for (CompletableFuture<List<Finding>> f : futures) {
                        List<Finding> batchFindings = f.get();
                        allFindings.addAll(batchFindings);
                        for (Finding finding : batchFindings) {
                            if (finding.getModuleName() != null) moduleSet.add(finding.getModuleName());
                        }
                    }
                } finally {
                    executor.shutdown();
                }
                log.info("Deep scan complete: {} findings from {} files in {} batches",
                        allFindings.size(), filtered.size(), batches.size());
            } else {
                // 无 hash 范围且未开深度扫描：不应该到达这里（Orchestrator 会设置 root..HEAD hash）
                // 但保留 legacy 作为兜底
                log.info("Scan mode without deep scan flag — falling back to legacy");
                return reviewLegacy(context);
            }
        }

        // 后处理管线
        allFindings = runPipeline(allFindings, context);

        // 构建 CodeReviewResult（兼容旧接口）
        return buildResultFromFindings(allFindings, moduleSet.size(), context);
    }

    // ================================================================
    // Step 1: 文件预筛选
    // ================================================================

    /**
     * 跳过 DELETE 类型文件和非代码文件。
     */
    List<FileDiff> preFilter(List<FileDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) return Collections.emptyList();

        List<FileDiff> filtered = new ArrayList<>();
        for (FileDiff diff : diffs) {
            // 跳过已删除的文件
            String changeType = diff.getChangeType();
            if ("DELETE".equalsIgnoreCase(changeType) || "deleted".equalsIgnoreCase(changeType)) {
                log.debug("Pre-filter: skipping deleted file {}", diff.getFilePath());
                continue;
            }

            // 跳过非代码文件
            String path = diff.getFilePath();
            if (path != null && isNonCodeFile(path)) {
                log.debug("Pre-filter: skipping non-code file {}", path);
                continue;
            }

            filtered.add(diff);
        }

        log.info("Pre-filter: {} → {} files (skipped {} non-code/deleted)",
                diffs.size(), filtered.size(), diffs.size() - filtered.size());
        return filtered;
    }

    /** 判断是否为非代码文件（图片、二进制、文档等） */
    private boolean isNonCodeFile(String path) {
        String lower = path.toLowerCase();
        // 二进制/图片/文档/字体
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".ico") || lower.endsWith(".svg")
                || lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt")
                || lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".war")
                || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".bz2")
                || lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf")
                || lower.endsWith(".eot") || lower.endsWith(".mp3") || lower.endsWith(".mp4")
                || lower.endsWith(".avi") || lower.endsWith(".mov")
                || lower.endsWith(".lock") || lower.endsWith(".sum");
    }

    // ================================================================
    // Step 2: 按业务链路分组
    // ================================================================

    /**
     * 按文件路径提取业务领域名并分组，每组构建审查背景上下文。
     */
    Map<String, ReviewGroup> groupByBusinessLink(List<FileDiff> diffs, CodeReviewContext context) {
        Map<String, List<FileDiff>> byModule = new LinkedHashMap<>();

        for (FileDiff diff : diffs) {
            String module = ModulePathResolver.resolveModule(diff.getFilePath());
            byModule.computeIfAbsent(module, k -> new ArrayList<>()).add(diff);
        }

        // 全局 background（code-review-graph 架构分析）
        String globalBg = buildGraphBackground(context);

        Map<String, ReviewGroup> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<FileDiff>> entry : byModule.entrySet()) {
            String module = entry.getKey();
            List<FileDiff> files = entry.getValue();

            // 每组 background = 模块信息 + 全局架构分析
            StringBuilder bg = new StringBuilder();
            bg.append("## 模块: ").append(module).append("（").append(files.size()).append(" 个文件）\n");
            bg.append("文件列表:\n");
            for (FileDiff f : files) {
                bg.append("- ").append(f.getFilePath())
                        .append(" (").append(f.getChangeType() != null ? f.getChangeType() : "MODIFY").append(")\n");
            }
            if (globalBg != null && !globalBg.isEmpty()) {
                bg.append("\n").append(globalBg);
            }

            groups.put(module, new ReviewGroup(module, files, bg.toString()));
        }

        log.info("Grouped {} files into {} module(s): {}", diffs.size(), groups.size(), groups.keySet());
        return groups;
    }

    // ================================================================
    // Step 3: 逐组审查
    // ================================================================

    // ================================================================
    // Step 3: 审查
    // ================================================================

    /**
     * diff 模式：按模块分组，每组一次 LLM 调用（打包该组所有文件 diff）。
     *
     * <p>设计原因：OCR 的 code_review_diff 对每个文件执行 Plan+Main 两阶段 LLM 调用，
     * 138 文件 = 276 次调用，8 并发也要 12+ 分钟，远超 30 分钟超时。
     * 而 legacy 把所有 diff 打包在一个 prompt 只需 10-30 秒。
     *
     * <p>折中方案：按模块分组（5-7 组），每组打包该组所有文件 diff 到一个 prompt，
     * 要求 LLM 输出结构化 JSON（OcrComment 格式），然后走完整的
     * blame → crossValidate → secret → verify 后处理管线。
     *
     * <p>预期效果：5-7 组 × 4 并发 ≈ 2 轮 LLM 调用 ≈ 1-2 分钟完成。</p>
     */
    List<Finding> reviewDiffByModule(List<FileDiff> diffs, CodeReviewContext context) throws Exception {
        Map<String, ReviewGroup> groups = groupByBusinessLink(diffs, context);
        int concurrency = Math.min(scanConcurrency, groups.size());
        log.info("Diff review: {} files → {} module groups, {} concurrency",
                diffs.size(), groups.size(), concurrency);

        // 构建跨模块依赖索引：FQCN → FileDiff
        // 键 = 全限定类名（如 com.example.UserService），值 = 该类的变更 diff
        Map<String, FileDiff> classIndex = buildClassIndex(diffs);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<CompletableFuture<List<Finding>>> futures = new ArrayList<>();

        try {
            for (ReviewGroup group : groups.values()) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        log.info("Module '{}': {} files → calling LLM...", group.name, group.files.size());
                        // 构建本模块的跨模块上下文：本模块 import 了哪些其他模块的类，附带具体 diff
                        String crossRefs = buildCrossModuleContext(group, classIndex);
                        List<Finding> findings = reviewModuleGroupDirect(group, context, crossRefs);
                        log.info("Module '{}': {} findings", group.name, findings.size());
                        return findings;
                    } catch (Exception e) {
                        log.error("Module '{}' review failed: {}", group.name, e.getMessage());
                        return Collections.<Finding>emptyList();
                    }
                }, executor));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Finding> allFindings = new ArrayList<>();
            for (CompletableFuture<List<Finding>> f : futures) {
                allFindings.addAll(f.get());
            }
            log.info("Diff review complete: {} findings from {} files in {} groups",
                    allFindings.size(), diffs.size(), groups.size());
            return allFindings;
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 构建全限定类名 → FileDiff 索引。
     * 遍历所有变更的 Java 文件，解析 package + class 名，建立 FQCN → diff 映射。
     */
    private Map<String, FileDiff> buildClassIndex(List<FileDiff> diffs) {
        Map<String, FileDiff> index = new LinkedHashMap<>();
        for (FileDiff diff : diffs) {
            if (!JavaFileParser.isJavaFile(diff.getFilePath())) continue;
            String source = diff.getNewContent();
            if (source == null || source.isEmpty()) continue;
            JavaFileParser parser = new JavaFileParser(source);
            String pkg = parser.getPackageName();
            if (pkg == null || pkg.isEmpty()) continue;
            for (String className : parser.getClassNames()) {
                String fqcn = pkg + "." + className;
                index.put(fqcn, diff);
            }
        }
        log.debug("Built class index: {} FQCN entries from {} diffs", index.size(), diffs.size());
        return index;
    }

    /**
     * 构建本模块的跨模块上下文文本。
     * 扫描本组所有文件的 import，找出哪些 import 对应了其他模块的变更类，
     * 附带被引用类的实际 diff 内容，让 LLM 能精确判断接口兼容性。
     *
     * @return 跨模块影响描述文本，如果没有跨模块依赖则返回空字符串
     */
    private String buildCrossModuleContext(ReviewGroup group, Map<String, FileDiff> classIndex) {
        // 本组内的文件路径集合
        Set<String> ownFiles = new LinkedHashSet<>();
        for (FileDiff f : group.files) {
            ownFiles.add(f.getFilePath());
        }

        // FQCN → FileDiff，且该 FileDiff 不属于本组
        Map<String, FileDiff> externalDeps = new LinkedHashMap<>();
        for (FileDiff f : group.files) {
            if (!JavaFileParser.isJavaFile(f.getFilePath())) continue;
            String source = f.getNewContent();
            if (source == null || source.isEmpty()) continue;
            JavaFileParser parser = new JavaFileParser(source);
            for (String imp : parser.getImports()) {
                if (imp.startsWith("java.") || imp.startsWith("javax.") || imp.startsWith("sun.")) continue;
                if (!imp.contains(".")) continue;
                FileDiff target = classIndex.get(imp);
                if (target != null && !ownFiles.contains(target.getFilePath())) {
                    externalDeps.put(imp, target);
                }
            }
        }

        if (externalDeps.isEmpty()) return "";

        // 防止重复：同一类可能被多个文件 import，只展示一次
        // 但不同 import 指向不同 FileDiff 时可以有多条
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## ⚠️ 跨模块影响（请重点检查接口兼容性）\n\n");
        sb.append("本模块的以下文件导入了其他模块变更了的类，请注意不要遗漏跨模块问题：\n\n");

        Set<String> shown = new LinkedHashSet<>();
        for (Map.Entry<String, FileDiff> entry : externalDeps.entrySet()) {
            String importedFqcn = entry.getKey();
            FileDiff targetDiff = entry.getValue();
            if (shown.contains(importedFqcn)) continue;
            shown.add(importedFqcn);

            sb.append("### 外部变更类: `").append(importedFqcn).append("`\n");
            sb.append("  - 所在文件: ").append(targetDiff.getFilePath()).append("\n");

            // 附带实际 diff 内容（截断到 2000 字符，一个类变更通常不会太大）
            if (targetDiff.getUnifiedDiff() != null && !targetDiff.getUnifiedDiff().isEmpty()) {
                String hunk = targetDiff.getUnifiedDiff();
                if (hunk.length() > 2000) {
                    hunk = hunk.substring(0, 2000) + "\n... [diff truncated]";
                }
                sb.append("  - 变更内容:\n```diff\n").append(hunk).append("\n```\n");
            }
            sb.append("\n");
        }

        sb.append("**请额外检查**：\n");
        sb.append("- 调用方是否需要适配新的返回类型、参数或异常声明\n");
        sb.append("- 是否有被删除的方法或类在其他模块中仍在被使用\n");
        sb.append("- 接口语义是否发生了不兼容的变化\n");
        return sb.toString();
    }

    /**
     * 对一个业务链路组直接调用 LLM（不走 OCR），打包该组所有文件 diff。
     * LLM 输出结构化 JSON，解析为 OcrComment → Finding。
     */
    List<Finding> reviewModuleGroupDirect(ReviewGroup group, CodeReviewContext context, String crossRefs) throws Exception {
        String prompt = buildModuleReviewPrompt(group, context, crossRefs);
        String response = callLlm(prompt);

        // LLM 可能输出带 markdown 代码块的 JSON，需要剥离
        String json = extractJson(response);
        if (json.isEmpty()) {
            log.warn("Module '{}': LLM returned no valid JSON, raw={}", group.name,
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);
            return Collections.emptyList();
        }

        OcrReviewResponse ocrResult = parseOcrResult(group.name, json);
        if (ocrResult == null) {
            return Collections.emptyList();
        }

        List<Finding> findings = new ArrayList<>();
        List<OcrComment> comments = ocrResult.getComments();
        if (comments != null) {
            for (OcrComment cm : comments) {
                Finding f = Finding.fromOcrComment(cm);
                // 直接用组名作为模块名（比事后从路径重新解析更可靠）
                f.setModuleName(group.name);
                findings.add(f);
            }
        }

        log.info("Module '{}': {} ocr comments → {} findings",
                group.name, comments != null ? comments.size() : 0, findings.size());
        return findings;
    }

    /**
     * 构建模块级审查 prompt：项目信息 + 该组所有文件 diff + 结构化 JSON 输出指令。
     */
    private String buildModuleReviewPrompt(ReviewGroup group, CodeReviewContext context, String crossRefs) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个资深的 Java 代码审查专家。请审查以下代码变更。\n\n");
        sb.append("## 项目信息\n");
        sb.append("- 项目：").append(context.getProjectName() != null ? context.getProjectName() : "unknown").append("\n");
        sb.append("- 分支：").append(context.getBranch() != null ? context.getBranch() : "unknown").append("\n\n");

        sb.append("## 变更文件（模块: ").append(group.name).append("，共 ").append(group.files.size()).append(" 个文件）\n\n");
        for (FileDiff diff : group.files) {
            String changeLabel = diff.getChangeType() != null ? diff.getChangeType() : "MODIFY";
            sb.append("### ").append(diff.getFilePath()).append(" (").append(changeLabel).append(")\n");
            if (diff.getUnifiedDiff() != null && !diff.getUnifiedDiff().isEmpty()) {
                // 截断过大的 diff（>8000 字符）避免超出 LLM 上下文
                String diffContent = diff.getUnifiedDiff();
                if (diffContent.length() > 8000) {
                    diffContent = diffContent.substring(0, 8000) + "\n... [truncated, total " + diffContent.length() + " chars]";
                }
                sb.append("```diff\n").append(diffContent).append("\n```\n\n");
            } else if (diff.getNewContent() != null && !diff.getNewContent().isEmpty()) {
                String content = diff.getNewContent();
                if (content.length() > 4000) {
                    content = content.substring(0, 4000) + "\n... [truncated]";
                }
                sb.append("```java\n").append(content).append("\n```\n\n");
            }
        }

        // 架构上下文
        if (group.background != null && !group.background.isEmpty()) {
            sb.append("## 架构上下文\n").append(group.background).append("\n\n");
        }

        // 跨模块依赖上下文
        if (crossRefs != null && !crossRefs.isEmpty()) {
            sb.append(crossRefs);
        }

        sb.append("## 审查要求\n\n");
        sb.append("⚠️ **Diff 模式禁区（严禁报告的误判类型）**：\n");
        sb.append("- **严禁报告「类/Bean/方法/字段不存在」** — 你只能看到变更文件，未变更的代码对你不可见。\n");
        sb.append("  看到 @Autowired 的 bean 在 diff 中找不到定义 → 不代表不存在，不要报。\n");
        sb.append("  看到 import 了某个类但在 diff 中看不到 → 正常，不要报。\n");
        sb.append("  看到 @Async(\"xxx\") 但 diff 中没有 Executor bean → 大概率在其他文件定义了，不要报。\n");
        sb.append("- **严禁报告「依赖版本升级的风险」除非你能从 diff 中确认真的不兼容** — \n");
        sb.append("  版本号变了不代表有问题。只有当你看到新版本 API 确实与调用代码不兼容时才能报。\n");
        sb.append("  如果只是版本号变了但不确定影响，不要报 —— 你的 confidence 应该 < 0.5。\n\n");
        sb.append("**置信度校准规则**：\n");
        sb.append("- 确定是缺陷（有明确证据、diff 中就能证实的）→ confidence 0.85-0.99\n");
        sb.append("- 有趋势/特征但不完全确定（如代码模式像 bug，但缺少上下文证实）→ confidence ≤ 0.70\n");
        sb.append("- 纯猜测/不确定/「可能有风险」/「建议检查是否有」→ **不要报**，这不是代码审查要做的事\n");
        sb.append("- 如果你在 content 中写了「可能」「也许」「不确定」「需要验证」「建议检查是否有」，\n");
        sb.append("  说明你自己都不确定，此时 confidence 必须 ≤ 0.70\n\n");
        sb.append("请逐文件逐行审查以上代码变更，输出严格的结构化 JSON。\n\n");
        sb.append("**输出格式**（一个 JSON 对象，不要 markdown 代码块标记）：\n");
        sb.append("```\n");
        sb.append("{\n");
        sb.append("  \"comments\": [\n");
        sb.append("    {\n");
        sb.append("      \"path\": \"文件路径（相对于项目根目录）\",\n");
        sb.append("      \"category\": \"问题分类代码（见下方清单）\",\n");
        sb.append("      \"content\": \"问题描述（简洁明确，说明什么问题、为什么是问题）\",\n");
        sb.append("      \"existingCode\": \"现有问题代码\",\n");
        sb.append("      \"suggestionCode\": \"建议修改后的代码（如适用）\",\n");
        sb.append("      \"startLine\": 行号（整数，对应 diff 中 @@ -a,b +c,d @@ 的 +c 侧行号）,\n");
        sb.append("      \"endLine\": 行号（整数）,\n");
        sb.append("      \"thinking\": \"分析推理过程\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("**审查清单 — 请逐项检查每处变更，发现问题时在 category 字段标注对应代码**：\n\n");
        sb.append("⚠️ **分类优先级（当一个问题命中多个分类时，选优先级最高的）**：\n");
        sb.append("  SECRET_EXPOSURE > SECURITY > TRANSACTION > CONCURRENCY > NPE > RESOURCE_LEAK >\n");
        sb.append("  ERROR_HANDLING > ARCHITECTURE > LOGIC_ERROR > PERFORMANCE > DEPENDENCY >\n");
        sb.append("  HARDCODED > DEAD_CODE > CODE_STYLE > OTHER\n");
        sb.append("  例：`@GetMapping` 做写操作且缺少权限校验 → SECURITY（不是 CODE_STYLE）\n");
        sb.append("  例：硬编码了密码 → SECRET_EXPOSURE（不是 HARDCODED）\n\n");
        sb.append("P0 阻断（必须检查，发现即阻断）：\n");
        sb.append("□ SECURITY - SQL注入：用户输入拼接到SQL/HQL/JPA原生查询中？MyBatis ${} 而非 #{}？\n");
        sb.append("□ SECURITY - XSS/权限绕过：用户输入未转义输出？敏感操作缺少 @PreAuthorize 或角色校验？\n");
        sb.append("□ SECURITY - 反序列化漏洞：ObjectInputStream.readObject() 从不可信来源读取？是否有白名单校验？\n");
        sb.append("□ SECURITY - SSRF：URL/URI 参数来自用户输入且未校验？RestTemplate/HttpClient 访问用户指定的 URL？\n");
        sb.append("□ SECURITY - 路径穿越：文件路径拼接了用户输入？是否包含 ../ 或绝对路径绕过？\n");
        sb.append("□ SECURITY - XXE：XML 解析是否禁用了外部实体（DocumentBuilderFactory.setExpandEntityReferences(false)）？\n");
        sb.append("□ SECRET_EXPOSURE - 敏感信息：**仅限 Java 代码中**硬编码密码/Token/API密钥/AccessKey/Secret？注意：yml/xml/properties 配置文件中的密码是正常部署配置，不要报。\n");
        sb.append("□ TRANSACTION - 事务：写操作（INSERT/UPDATE/DELETE）是否有 @Transactional？事务传播/回滚策略是否正确？\n\n");
        sb.append("P1 高危（重点检查）：\n");
        sb.append("□ NPE - 空指针：方法返回值/参数/集合元素使用前是否判空？Optional.get()/Stream.findFirst().get() 有无保护？\n");
        sb.append("□ CONCURRENCY - 并发/死锁：共享可变变量是否线程安全（synchronized/volatile/AtomicXxx）？synchronized 块是否嵌套（死锁风险）？HashMap 是否误用于多线程（应改用 ConcurrentHashMap）？\n");
        sb.append("□ RESOURCE_LEAK - 资源泄漏：Stream/Connection/IO流是否在 finally 或 try-with-resources 中关闭？\n");
        sb.append("□ ERROR_HANDLING - 异常处理：catch 块是否为空？是否吞异常不记录？finally 块中是否有 return 语句（会吞掉异常）？\n");
        sb.append("□ ARCHITECTURE - 架构：是否存在循环依赖？Controller 直接调 DAO（应经过 Service）？工具类有无状态？\n");
        sb.append("□ LOGIC_ERROR - 逻辑错误：条件判断/计算逻辑是否有误？边界值（null/空集合/0/负数）是否处理？equals/hashCode 是否成对重写？BigDecimal 是否用了 new BigDecimal(double)（精度丢失）？\n");
        sb.append("□ COMPILE_ERROR - 编译错误：字符串字面量缺少闭合引号？缺少分号？引用了不存在的类/方法？注解参数类型不对？import 了但未使用的类？\n\n");
        sb.append("P2 中危（注意检查）：\n");
        sb.append("□ PERFORMANCE - 性能：循环内是否有数据库调用（N+1）？是否有不必要的对象创建？字符串拼接是否用 StringBuilder？\n");
        sb.append("□ DEPENDENCY - 依赖：是否引用了 SNAPSHOT/过期/有已知漏洞的版本？是否有未使用的 import？\n\n");
        sb.append("P3 低危（顺带指出）：\n");
        sb.append("□ HARDCODED - 硬编码：魔法数字、写死的配置值/URL/路径、未提取常量？\n");
        sb.append("□ CODE_STYLE - 代码风格：命名不规范、缺少必要注释、GET 请求执行写操作（非RESTful）？\n");
        sb.append("□ DEAD_CODE - 冗余/死代码：定义了但从未调用的方法/变量？永远不执行的分支（if(false)/return后的代码）？重复代码块？不必要的 import？\n\n");
        sb.append("**evidence 字段要求（必须遵守）**：\n");
        sb.append("- existingCode 必须是 diff 中对应的**实际源码**，从 ```diff 代码块中复制，不要自己编造或概括\n");
        sb.append("- 如果涉及多行代码，完整复制，太长时可以在非关键部分用 \"... [省略中间N行] ...\" 代替\n");
        sb.append("- **严禁**在 existingCode 中用文字描述代替源码（如「定义了三个相同 id 的 select」这种）\n\n");
        sb.append("□ DEAD_CODE - 冗余/死代码：定义了但从未调用的方法/变量？永远不执行的分支（if(false)/return后的代码）？重复代码块？不必要的 import？\n\n");
        sb.append("**要求**：\n");
        sb.append("1. 每个 comment 必须包含 path、category、content、startLine、endLine 字段\n");
        sb.append("2. category 必须填写清单中对应的分类代码（如 NPE、SECURITY、PERFORMANCE 等）\n");
        sb.append("3. startLine 对应 diff 中新增/修改代码在原文件中的行号\n");
        sb.append("4. 如果没有发现问题，返回 {\"comments\": []}\n");
        sb.append("5. 只输出 JSON，不要有任何其他文字或解释\n");
        sb.append("6. 不要用 ```json ``` 包裹，直接输出 { 开头\n");
        sb.append("7. **禁止使用省略号（...）** — content/thinking/existingCode/suggestionCode 都输出完整内容，不截断不缩写");

        return sb.toString();
    }

    /**
     * 从 LLM 响应中提取 JSON 对象。
     * LLM 可能输出纯 JSON，也可能用 markdown 代码块包裹。
     */
    static String extractJson(String response) {
        if (response == null || response.trim().isEmpty()) return "";
        String text = response.trim();

        // 尝试找 ```json ... ``` 或 ``` ... ```
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

        // 直接找第一个 { 到最后一个 }
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1);
        }

        return "";
    }

    /**
     * 解析 LLM 返回的 OCR JSON 结果。
     * LLM 已开启 JSON 模式，直接解析；失败时修复字符串值中未转义字符后重试。
     * 返回 null 表示解析失败。
     */
    private OcrReviewResponse parseOcrResult(String moduleName, String json) {
        // 直接解析
        try {
            return MAPPER.readValue(json, OcrReviewResponse.class);
        } catch (Exception e) {
            log.debug("Module '{}': direct parse failed, trying repair: {}", moduleName, e.getMessage());
        }

        // 修复字符串值中未转义字符后重试
        String repaired = repairJsonStringValues(json);
        if (!repaired.equals(json)) {
            try {
                OcrReviewResponse result = MAPPER.readValue(repaired, OcrReviewResponse.class);
                log.info("Module '{}': JSON repaired successfully", moduleName);
                return result;
            } catch (Exception e) {
                log.debug("Module '{}': repaired JSON still invalid: {}", moduleName, e.getMessage());
            }
        }

        log.error("Module '{}': failed to parse LLM JSON even after repair — raw={}", moduleName,
                json.length() > 300 ? json.substring(0, 300) + "..." : json);
        return null;
    }

    /**
     * 修复 JSON 字符串值中常见的未转义字符。
     * 处理 LLM 在代码片段字段（existingCode、suggestionCode 等）中
     * 输出未转义的双引号、换行符、反斜杠等问题。
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
                    // 检查下一个非空白字符，判断这是字符串结束还是未转义的引号
                    int next = nextNonWhitespace(json, i + 1);
                    if (next == ':' || next == ',' || next == '}' || next == ']' || next < 0) {
                        // 后面是 JSON 结构字符，这是字符串正常结束
                        inString = false;
                        sb.append(c);
                    } else {
                        // 字符串内部的未转义引号，转义它
                        sb.append('\\').append(c);
                    }
                } else if (c == '\n' || c == '\r' || c == '\t') {
                    // 字符串内部的换行/tab，转义
                    sb.append(c == '\n' ? "\\n" : c == '\r' ? "\\r" : "\\t");
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
                if (c == '"') {
                    inString = true;
                }
            }
        }
        return sb.toString();
    }

    private static int nextNonWhitespace(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                return c;
            }
        }
        return -1;
    }

    /**
     * 对一个业务链路组调用 OCR MCP 审查，产出 Finding 列表。
     */
    List<Finding> reviewGroup(ReviewGroup group, CodeReviewContext context) throws Exception {
        // 确定 tool 名称
        String toolName;
        Map<String, Object> args = new HashMap<>();
        args.put("repo_dir", context.getRepoPath() != null ? context.getRepoPath() : "");
        args.put("background", group.background);
        if (getModel() != null && !getModel().isEmpty()) {
            args.put("model", getModel());
        }

        // 拼装该组的文件路径列表
        String paths = group.files.stream()
                .map(FileDiff::getFilePath)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));
        if (paths.isEmpty()) {
            log.warn("Group '{}': no valid file paths, skipping review", group.name);
            return Collections.emptyList();
        }
        args.put("path", paths);

        if (context.getSinceHash() != null && context.getUntilHash() != null) {
            toolName = "code_review_diff";
            args.put("from", context.getSinceHash());
            args.put("to", context.getUntilHash());
        } else {
            toolName = "code_scan";
        }

        log.info("Reviewing group '{}': tool={}, {} files, path sample: {}",
                group.name, toolName, group.files.size(),
                paths.length() > 80 ? paths.substring(0, 80) + "..." : paths);

        String ocrJson = ocrmcpClient.callTool(toolName, args);
        OcrReviewResponse ocrResult = MAPPER.readValue(ocrJson, OcrReviewResponse.class);

        // OcrComment → Finding 转换
        List<Finding> findings = new ArrayList<>();
        List<OcrComment> comments = ocrResult.getComments();
        if (comments != null) {
            for (OcrComment cm : comments) {
                Finding f = Finding.fromOcrComment(cm);
                f.setModuleName(group.name); // Phase 8 模块趋势页聚合键
                findings.add(f);
            }
        }

        log.info("Group '{}': {} ocr comments → {} findings", group.name,
                comments != null ? comments.size() : 0, findings.size());
        return findings;
    }

    // ================================================================
    // 深度扫描：分批 + code_scan
    // ================================================================

    /**
     * 将过滤后的文件按模块分组，再按 batchSize 拆分为批次。
     * 优先保持同模块文件在一起，大模块才切分到多个批次。
     */
    List<List<FileDiff>> splitIntoBatches(List<FileDiff> diffs, CodeReviewContext context, int batchSize) {
        Map<String, ReviewGroup> groups = groupByBusinessLink(diffs, context);
        List<List<FileDiff>> batches = new ArrayList<>();

        for (ReviewGroup group : groups.values()) {
            List<FileDiff> files = group.files;
            if (files.size() <= batchSize) {
                batches.add(files);
            } else {
                for (int i = 0; i < files.size(); i += batchSize) {
                    batches.add(new ArrayList<>(files.subList(i, Math.min(i + batchSize, files.size()))));
                }
            }
        }
        log.info("Split {} files into {} batches (max {} per batch, {} modules)",
                diffs.size(), batches.size(), batchSize, groups.size());
        return batches;
    }

    /**
     * 对一个批次的文件调用 OCR code_scan。
     * background 中附带同批次文件列表，弥补 LLM 看不到其他批次文件的上下文损失。
     */
    List<Finding> reviewBatch(List<FileDiff> batch, CodeReviewContext context, String graphBg, int batchIdx) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("repo_dir", context.getRepoPath() != null ? context.getRepoPath() : "");

        // 构建 background：全局架构信息（由调用方缓存传入） + 同批次文件列表
        String bg = graphBg
                + "\n\n## 本批次审查文件（共 " + batch.size() + " 个，批次 " + (batchIdx + 1) + "）\n"
                + "以下文件在此次同一批次中审查，请注意它们之间的交叉引用和调用关系：\n";
        for (FileDiff f : batch) {
            bg += "- " + f.getFilePath()
                    + " (" + (f.getChangeType() != null ? f.getChangeType() : "MODIFY") + ")\n";
        }
        args.put("background", bg);

        if (getModel() != null && !getModel().isEmpty()) {
            args.put("model", getModel());
        }

        String paths = batch.stream()
                .map(FileDiff::getFilePath)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));
        if (paths.isEmpty()) {
            log.warn("Batch {}: no valid file paths, skipping", batchIdx + 1);
            return Collections.emptyList();
        }
        args.put("path", paths);

        log.info("Batch {}: calling code_scan for {} files", batchIdx + 1, batch.size());

        String ocrJson = ocrmcpClient.callTool("code_scan", args);
        OcrReviewResponse ocrResult = MAPPER.readValue(ocrJson, OcrReviewResponse.class);

        List<Finding> findings = new ArrayList<>();
        List<OcrComment> comments = ocrResult.getComments();
        if (comments != null) {
            for (OcrComment cm : comments) {
                Finding f = Finding.fromOcrComment(cm);
                String module = ModulePathResolver.resolveModule(cm.getPath());
                f.setModuleName(module != null ? module : "other");
                findings.add(f);
            }
        }

        log.info("Batch {}: {} ocr comments → {} findings", batchIdx + 1,
                comments != null ? comments.size() : 0, findings.size());
        return findings;
    }

    // ================================================================
    // 后处理管线
    // ================================================================

    /**
     * 执行审查后处理管线。
     *
     * <p>管线顺序：
     * <pre>
     *   List&lt;Finding&gt; raw
     *     │
     *     ├─ Phase 5: FindingBlameTracer.trace()          ← 对 P0/P1/P2 做 git blame
     *     ├─ Phase 6: ReviewLlmService.crossValidate()     ← 对 P0/P1/P2 双 LLM 交叉验证
     *     ├─ Phase 3: SecretDetector.detectAndSanitize()   ← 脱敏 + SECRET_EXPOSURE
     *     ├─ Phase 3: FindingVerifier.verify()            ← 行号校验/去重/误报/完整性
     *     └─ 输出
     * </pre>
     *
     * <p>管线顺序不可变——BlameTracer 需要完整原文做 git blame，
     * ReviewLlmService 需要 blame 结果作为输入，SecretDetector 必须最后做脱敏。</p>
     */
    List<Finding> runPipeline(List<Finding> findings, CodeReviewContext context) {
        if (findings == null || findings.isEmpty()) return Collections.emptyList();

        // ===== Phase 5: 交叉验证前去重（同文件+同行号+同分类 → 合并）=====
        // 避免跨模块组产生的重复 Finding 浪费 review LLM 调用
        List<Finding> deduped = dedupBeforePipeline(findings);

        // ===== Phase 5: Blame 追溯（P0/P1/P2）=====
        List<Finding> traced = findingBlameTracer.trace(deduped, context.getRepoPath());

        // ===== Phase 6: 双 LLM 交叉验证（P0/P1/P2）=====
        List<Finding> validated = reviewLlmService.crossValidate(traced, context);

        // ===== Phase 3: SecretDetector — 脱敏 + 新增 SECRET_EXPOSURE =====
        List<Finding> newSecretFindings = secretDetector.detectAndSanitize(validated);

        // ===== Phase 3: FindingVerifier — 行号校验/去重/误报/完整性 =====
        FilterResult fr = findingVerifier.verify(validated, context.getRepoPath());
        List<Finding> verified = fr.toPipelineOutput();

        // 追加 SecretDetector 新产出的 SECRET_EXPOSURE Finding
        verified.addAll(newSecretFindings);

        int dupedCount = findings.size() - deduped.size();
        log.info("Pipeline complete: {} findings ({} deduped) → {} after verify + {} secret → {} total",
                findings.size(), dupedCount, verified.size() - newSecretFindings.size(),
                newSecretFindings.size(), verified.size());
        return verified;
    }

    /**
     * 管线前去重：同文件 + 同行号范围 + 同分类 的 Finding 合并为一条。
     * 只做确定性去重（不丢信息），误报/置信度判定留给下游。
     */
    private List<Finding> dedupBeforePipeline(List<Finding> findings) {
        Map<String, Finding> seen = new LinkedHashMap<>();
        int dupes = 0;
        for (Finding f : findings) {
            String key = dedupKey(f);
            Finding existing = seen.get(key);
            if (existing != null) {
                dupes++;
                // 保留证据链更丰富的（更长的 evidence）
                if (f.getEvidence() != null && (existing.getEvidence() == null
                        || f.getEvidence().length() > existing.getEvidence().length())) {
                    seen.put(key, f);
                }
            } else {
                seen.put(key, f);
            }
        }
        if (dupes > 0) {
            log.info("Pipeline pre-dedup: removed {} duplicates, {} → {}",
                    dupes, findings.size(), seen.size());
        }
        return new ArrayList<>(seen.values());
    }

    /** 去重键：文件 + 起始行 + 结束行 + 分类 */
    private static String dedupKey(Finding f) {
        String cat = f.getCategory() != null ? f.getCategory().name() : "?";
        return (f.getFile() != null ? f.getFile() : "?") + "#L"
                + f.getStartLine() + "-" + f.getEndLine() + "#" + cat;
    }

    // ================================================================
    // Finding → CodeReviewResult 转换（过渡期兼容）
    // ================================================================

    /**
     * 从最终 Finding 列表构建 CodeReviewResult。
     *
     * <p>Phase 8 之前，GenerationOrchestrator 仍需要 CodeReviewResult 来生成报告。
     * Phase 8 ReviewReportGenerator 全面切换到 Finding 渲染后，此方法可移除。</p>
     */
    private CodeReviewResult buildResultFromFindings(List<Finding> findings,
                                                      int moduleCount,
                                                      CodeReviewContext context) {
        CodeReviewResult result = new CodeReviewResult();
        result.setFindings(findings);

        // 统计
        long p0 = findings.stream().filter(f -> f.getSeverity() == FindingSeverity.BLOCKER).count();
        long p1 = findings.stream().filter(f -> f.getSeverity() == FindingSeverity.HIGH).count();
        long confirmed = findings.stream().filter(f -> f.getStatus() == FindingStatus.CONFIRMED).count();

        if (findings.isEmpty()) {
            result.setConclusion("通过");
            result.setRiskLevel("低");
            result.setKeyFindings("AI 审查未发现代码缺陷");
        } else {
            result.setConclusion(p0 > 0 ? "需修改" : "建议修改");
            result.setRiskLevel(p0 > 0 || p1 >= 3 ? "高" : (p1 > 0 ? "中" : "低"));
            result.setKeyFindings(String.format("发现 %d 个问题（P0=%d, P1=%d, 已确认=%d），涉及 %d 个模块",
                    findings.size(), p0, p1, confirmed, moduleCount));
        }

        result.setChangeSummary(context.getScopeDescription() != null
                ? context.getScopeDescription() : null);
        result.setCodeIssues(formatFindingsAsText(findings));
        result.setRawResponse(null);

        return result;
    }

    /** 将 Finding 列表格式化为可读文本（旧 CodeReviewResult.codeIssues 格式） */
    private String formatFindingsAsText(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return "（本次审查未发现代码缺陷）";
        }
        StringBuilder sb = new StringBuilder();
        for (Finding f : findings) {
            String sev = f.getSeverity() != null ? f.getSeverity().getLabel() : "未知";
            sb.append("- **[").append(sev).append("]** `").append(f.getFile()).append("`")
                    .append(" (第").append(f.getStartLine()).append("-").append(f.getEndLine()).append("行)");
            if (f.getModuleName() != null) {
                sb.append(" [").append(f.getModuleName()).append("]");
            }
            sb.append("\n");
            if (f.getEvidence() != null && !f.getEvidence().isEmpty()) {
                sb.append("  > ").append(truncate(f.getEvidence(), 200)).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ================================================================
    // Legacy 路径（保留完整实现作为 fallback）
    // ================================================================

    public CodeReviewResult reviewLegacy(CodeReviewContext context) {
        CodeReviewResult result = new CodeReviewResult();
        try {
            String prompt = buildReviewPrompt(context);
            String response = callLlm(prompt);
            result.setRawResponse(response);
            return parseResponse(response, result);
        } catch (Exception e) {
            log.warn("AI code review failed: {}", e.getMessage());
            result.setConclusion("审查失败");
            result.setRiskLevel("未知");
            result.setKeyFindings("AI 审查过程出错: " + e.getMessage());
            return result;
        }
    }

    // ================================================================
    // Graph 上下文构建
    // ================================================================

    /**
     * 将 code-review-graph 的分析结果转为审查背景文本。
     * 这段文本会作为 background 参数传给 OCR，LLM 在审查时能结合架构信息做判断。
     */
    private String buildGraphBackground(CodeReviewContext context) {
        String graphJson = context.getGraphAnalysisJson();

        // 尝试从 JSON 解析 ImpactScope
        ImpactScope scope = null;
        if (graphJson != null && !graphJson.isEmpty()) {
            try {
                CodeReviewGraph graph = MAPPER.readValue(graphJson, CodeReviewGraph.class);
                if (graph.getImpactScope() != null) {
                    scope = graph.getImpactScope();
                }
            } catch (Exception e) {
                // graphJson 可能不是标准的 CodeReviewGraph JSON，尝试从对象中直接取
                CodeReviewGraph graph = context.getGraph();
                if (graph != null && graph.getImpactScope() != null) {
                    scope = graph.getImpactScope();
                }
            }
        } else {
            // fallback: 从内存对象取
            CodeReviewGraph graph = context.getGraph();
            if (graph != null && graph.getImpactScope() != null) {
                scope = graph.getImpactScope();
            }
        }

        if (scope == null) {
            return "请全面审查代码缺陷：空指针、事务边界、并发安全、异常处理、资源释放。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是通过静态分析发现的架构信息，请结合进行代码审查：");

        sb.append("\n\n## 影响范围\n");
        sb.append("- 风险等级: ").append(scope.getRiskLevel()).append("\n");
        if (scope.getDirectlyAffectedFiles() != null) {
            sb.append("- 直接影响文件数: ").append(scope.getDirectlyAffectedFiles().size()).append("\n");
        }
        if (scope.getIndirectlyAffectedFiles() != null) {
            sb.append("- 间接影响文件数: ").append(scope.getIndirectlyAffectedFiles().size()).append("\n");
        }

        if (scope.getRiskSignals() != null && !scope.getRiskSignals().isEmpty()) {
            sb.append("\n## 架构风险信号\n");
            for (String signal : scope.getRiskSignals()) {
                sb.append("- ").append(signal).append("\n");
            }
        }

        sb.append("\n## 审查重点\n");
        sb.append("1. 空指针风险、事务边界(@Transactional)、并发安全、资源释放、异常处理\n");
        sb.append("2. 被间接影响的文件是否也需要同步修改\n");
        sb.append("3. 架构风险信号指向的模块是否确实存在设计问题\n");

        return sb.toString();
    }

    // ================================================================
    // Legacy prompt / parsing（不变）
    // ================================================================

    private String buildReviewPrompt(CodeReviewContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个资深的 Java 代码审查专家。请审查以下代码变更。\n\n");
        sb.append("## 项目信息\n");
        sb.append("- 项目：").append(context.getProjectName() != null ? context.getProjectName() : "unknown").append("\n");
        sb.append("- 版本：").append(context.getProjectVersion() != null ? context.getProjectVersion() : "unknown").append("\n");
        sb.append("- 分支：").append(context.getBranch() != null ? context.getBranch() : "unknown").append("\n\n");

        List<FileDiff> diffs = context.getFileDiffs();
        if (diffs != null && !diffs.isEmpty()) {
            sb.append("## 变更文件及 Diff\n");
            for (FileDiff diff : diffs) {
                sb.append("### ").append(diff.getFilePath()).append(" (").append(diff.getChangeType()).append(")\n");
                if (diff.getUnifiedDiff() != null && diff.getUnifiedDiff().length() < 5000) {
                    sb.append("```diff\n").append(diff.getUnifiedDiff()).append("\n```\n\n");
                } else if (diff.getNewContent() != null) {
                    sb.append("```java\n").append(truncate(diff.getNewContent(), 3000)).append("\n```\n\n");
                }
            }
        }

        String graphJson = context.getGraphAnalysisJson();
        if (graphJson != null && !graphJson.isEmpty()) {
            sb.append("## code-review-graph 静态分析结果\n");
            sb.append("```json\n").append(graphJson).append("\n```\n\n");
        } else {
            CodeReviewGraph graph = context.getGraph();
            if (graph != null && graph.getNodes() != null && !graph.getNodes().isEmpty()) {
                sb.append("## 依赖关系图谱摘要\n");
                long changedFiles = graph.getNodes().stream().filter(n -> !"unchanged".equals(n.getChangeType())).count();
                sb.append("- 总节点数：").append(graph.getNodes().size()).append("\n");
                sb.append("- 变更节点数：").append(changedFiles).append("\n");
                sb.append("- 依赖边数：").append(graph.getEdges() != null ? graph.getEdges().size() : 0).append("\n");
                List<GraphEdge> imports = graph.getEdges() != null ?
                        graph.getEdges().stream().filter(e -> "imports".equals(e.getRelationType())).collect(Collectors.toList()) : null;
                if (imports != null && !imports.isEmpty()) {
                    sb.append("- 外部依赖数：").append(imports.size()).append("\n");
                }
                sb.append("\n");

                if (graph.getImpactScope() != null) {
                    ImpactScope scope = graph.getImpactScope();
                    sb.append("## 影响范围分析\n");
                    sb.append("- 直接影响文件：").append(scope.getDirectlyAffectedFiles().size()).append("\n");
                    sb.append("- 间接影响文件：").append(scope.getIndirectlyAffectedFiles().size()).append("\n");
                    sb.append("- 风险等级：").append(scope.getRiskLevel()).append("\n");
                    if (scope.getRiskSignals() != null && !scope.getRiskSignals().isEmpty()) {
                        sb.append("- 风险信号：\n");
                        for (String signal : scope.getRiskSignals()) {
                            sb.append("  - ").append(signal).append("\n");
                        }
                    }
                    sb.append("\n");
                }
            }
        }

        sb.append("请基于以上代码变更内容，输出结构化审查结果，严格按以下 7 个维度：\n\n");
        sb.append("### 1. 代码变更说明\n");
        sb.append("总结本次变更的核心目的和主要内容。\n\n");
        sb.append("### 2. 架构与依赖分析\n");
        sb.append("- 是否存在循环依赖或依赖反向（下层依赖上层）？\n");
        sb.append("- 是否存在架构分层违规（如 Controller 直接调 Repository）？\n\n");
        sb.append("### 3. 潜在代码缺陷\n");
        sb.append("逐文件审查，重点关注空指针、事务边界、并发安全、资源释放、API 兼容性、异常处理。\n\n");
        sb.append("### 4. 变更影响范围\n");
        sb.append("评估哪些模块/服务可能被间接影响，是否为向后兼容的变更。\n\n");
        sb.append("### 5. 测试建议\n");
        sb.append("哪些逻辑路径最需要测试覆盖，边界条件和异常场景。\n\n");
        sb.append("### 6. 代码规范与风格\n");
        sb.append("是否违反项目编码规范，命名是否合理。\n\n");
        sb.append("### 7. 审查结论\n");
        sb.append("**结论**: [通过/需修改/拒绝]\n");
        sb.append("**风险等级**: [低/中/高]\n");
        sb.append("**关键发现**: 列出必须修改的问题（如有）\n");
        sb.append("**建议**: 整体建议\n");

        return sb.toString();
    }

    private String callLlm(String prompt) {
        String provider = getProvider();
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) {
            throw new AiServiceException("API key not configured");
        }
        String apiUrl = getApiUrl();
        String model = getModel();
        log.info("Calling LLM for code review: provider={}, model={}", provider, model);
        return llmClient.call(provider, apiKey, apiUrl, model, prompt, 16384, "json_object");
    }

    private CodeReviewResult parseResponse(String response, CodeReviewResult result) {
        if (response == null || response.trim().isEmpty()) {
            result.setConclusion("审查失败");
            result.setRiskLevel("未知");
            result.setKeyFindings("LLM 返回为空");
            return result;
        }

        String[] sections = response.split("(?m)^###\\s+\\d+\\.\\s+");
        for (String section : sections) {
            section = section.trim();
            if (section.isEmpty()) continue;

            String headerLine = section.split("\n")[0].trim();
            String body = section.substring(headerLine.length()).trim();

            if (headerLine.contains("代码变更说明")) {
                result.setChangeSummary(body);
            } else if (headerLine.contains("架构与依赖分析")) {
                result.setArchitectureAnalysis(body);
            } else if (headerLine.contains("潜在代码缺陷")) {
                result.setCodeIssues(body);
            } else if (headerLine.contains("变更影响范围")) {
                result.setImpactAnalysis(body);
            } else if (headerLine.contains("测试建议")) {
                result.setTestSuggestions(body);
            } else if (headerLine.contains("审查结论")) {
                result.setConclusion(extractField(body, "结论"));
                result.setRiskLevel(extractField(body, "风险等级"));
                result.setKeyFindings(extractField(body, "关键发现"));
            }
        }

        if (result.getConclusion() == null) {
            if (response.contains("通过")) result.setConclusion("通过");
            else if (response.contains("需修改")) result.setConclusion("需修改");
            else if (response.contains("拒绝")) result.setConclusion("拒绝");
            else result.setConclusion("需修改");
        }
        if (result.getRiskLevel() == null) {
            if (response.contains("高风险") || response.contains("风险等级.*高")) result.setRiskLevel("高");
            else if (response.contains("中风险") || response.contains("风险等级.*中")) result.setRiskLevel("中");
            else result.setRiskLevel("低");
        }

        return result;
    }

    private String extractField(String text, String fieldName) {
        if (text == null) return null;
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("**" + fieldName + "**") || trimmed.startsWith(fieldName + ":")) {
                int idx = trimmed.indexOf(':');
                if (idx >= 0 && idx + 1 < trimmed.length()) {
                    return trimmed.substring(idx + 1).trim().replaceAll("^\\*+\\s*", "").replaceAll("\\*+$", "").trim();
                }
            }
        }
        return null;
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n... [truncated]";
    }

    private String readConfig(String key) {
        AiConfig config = aiConfigRepository.findByConfigKey(key);
        return config != null ? config.getConfigValue() : "";
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

    private String getApiUrl() {
        String customUrl = readConfig("llm.apiUrl");
        if (customUrl != null && !customUrl.trim().isEmpty()) return customUrl.trim();
        String provider = readConfig("llm.provider");
        String defaultUrl = DEFAULT_API_URLS.get(provider);
        return defaultUrl != null ? defaultUrl : "https://api.deepseek.com";
    }

    private String getModel() {
        String model = readConfig("llm.modelName");
        return (model != null && !model.isEmpty()) ? model : "deepseek-chat";
    }

    private String getProvider() {
        String provider = readConfig("llm.provider");
        return (provider != null && !provider.isEmpty()) ? provider : "deepseek";
    }
}
