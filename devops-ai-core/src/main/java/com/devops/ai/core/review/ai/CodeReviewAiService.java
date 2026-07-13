package com.devops.ai.core.review.ai;

import com.devops.ai.core.llm.LlmClient;
import com.devops.ai.core.crg.CrgClient;
import com.devops.ai.core.crg.CrgModels;
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

import com.devops.ai.core.review.model.GrepRequest;
import com.devops.ai.core.review.model.IterativeReviewResult;

@Component
public class CodeReviewAiService {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewAiService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
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
    private final GrepTracer grepTracer;
    private final CrgClient crgClient;

    @Value("${ocr.fallback-on-error:true}")
    private boolean ocrFallbackOnError;

    @Value("${ocr.scan-concurrency:4}")
    private int scanConcurrency;

    @Value("${ocr.scan-batch-size:20}")
    private int scanBatchSize;

    @Value("${ocr.deep-scan.max-source-chars:12000}")
    private int maxSourceChars;

    @Value("${ocr.deep-scan.retry-count:1}")
    private int retryCount;

    @Value("${ocr.deep-scan.max-chunk-chars:30000}")
    private int maxChunkChars;

    @Value("${ocr.deep-scan.max-grep-rounds:10}")
    private int maxGrepRounds;

    @Value("${ocr.deep-scan.max-grep-per-round:5}")
    private int maxGrepPerRound;

    private static final int MAX_FORMAT_RETRIES = 2;

    public CodeReviewAiService(LlmClient llmClient, AiConfigRepository aiConfigRepository,
                               ConfigEncryptor configEncryptor, OcrmcpClient ocrmcpClient,
                               FindingBlameTracer findingBlameTracer,
                               ReviewLlmService reviewLlmService,
                               SecretDetector secretDetector, FindingVerifier findingVerifier,
                               GrepTracer grepTracer, CrgClient crgClient) {
        this.llmClient = llmClient;
        this.aiConfigRepository = aiConfigRepository;
        this.configEncryptor = configEncryptor;
        this.ocrmcpClient = ocrmcpClient;
        this.findingBlameTracer = findingBlameTracer;
        this.reviewLlmService = reviewLlmService;
        this.secretDetector = secretDetector;
        this.findingVerifier = findingVerifier;
        this.grepTracer = grepTracer;
        this.crgClient = crgClient;
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
            log.error("OCR review failed, falling back to legacy: {}", e.getMessage(), e);
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

        // --- CRG 集成字段 ---
        CrgModels.CrgModuleSummary crgModuleSummary;      // Layer 1: 模块级摘要
        CrgModels.CrgFileClassification crgClassification; // Layer 2: 文件分类

        // 分类后使用的文件列表（core → 全量，edge → 轻审）
        transient List<FileDiff> coreFiles;
        transient List<FileDiff> edgeFiles;

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
            // scan 模式（无 hash 范围）：深度扫描按模块分组，每模块一次 LLM 调用
            if (context.isUseOcrDeepScan()) {
                List<Finding> findings = reviewDeepScanByModule(filtered, context);
                for (Finding f : findings) {
                    if (f.getModuleName() != null) moduleSet.add(f.getModuleName());
                }
                allFindings.addAll(findings);
                log.info("Deep scan complete: {} findings from {} files", findings.size(), filtered.size());
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

    /** 判断是否为非代码文件（图片、二进制、文档、配置文件等） */
    private boolean isNonCodeFile(String path) {
        String lower = path.toLowerCase();
        // 二进制/图片/文档/字体
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".ico") || lower.endsWith(".svg")
                || lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt")
                || lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".war")
                || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".bz2")
                || lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf")
                || lower.endsWith(".eot") || lower.endsWith(".mp3") || lower.endsWith(".mp4")
                || lower.endsWith(".avi") || lower.endsWith(".mov")
                || lower.endsWith(".lock") || lower.endsWith(".sum")) {
            return true;
        }
        // 配置文件（明文密码等不属于代码 bug）
        if (lower.endsWith(".properties") || lower.endsWith(".yml") || lower.endsWith(".yaml")
                || lower.endsWith(".setting") || lower.endsWith(".conf") || lower.endsWith(".cfg")
                || lower.endsWith(".env") || lower.endsWith(".ini") || lower.endsWith(".toml")) {
            return true;
        }
        // 部署/运维配置目录下的文件
        if (lower.contains("/deploy/") || lower.contains("\\deploy\\")
                || lower.contains("/config/") || lower.contains("\\config\\")) {
            return true;
        }
        return false;
    }

    // ================================================================
    // Step 2: 按业务链路分组
    // ================================================================

    /**
     * 按文件路径提取业务领域名并分组，每组构建审查背景上下文。
     *
     * <p>文件数少于 {@code minGroupSize} 的小组会被合并为一个 "small-modules" 组，
     * 减少 LLM 调用次数。</p>
     */
    Map<String, ReviewGroup> groupByBusinessLink(List<FileDiff> diffs, CodeReviewContext context) {
        Map<String, List<FileDiff>> byModule = new LinkedHashMap<>();

        for (FileDiff diff : diffs) {
            String module = ModulePathResolver.resolveModule(diff.getFilePath());
            byModule.computeIfAbsent(module, k -> new ArrayList<>()).add(diff);
        }

        // 全局 background（code-review-graph 架构分析）
        String globalBg = buildGraphBackground(context);

        // 小组合并：文件数 < minGroupSize 的组合并为 "small-modules"
        int minGroupSize = 3;
        List<FileDiff> smallFiles = new ArrayList<>();
        List<String> smallModuleNames = new ArrayList<>();
        Map<String, List<FileDiff>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, List<FileDiff>> entry : byModule.entrySet()) {
            if (entry.getValue().size() < minGroupSize) {
                smallFiles.addAll(entry.getValue());
                smallModuleNames.add(entry.getKey());
            } else {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        if (!smallFiles.isEmpty()) {
            merged.put("small-modules", smallFiles);
            log.info("Merged {} small modules ({}): {} files into 'small-modules'",
                    smallModuleNames.size(), smallModuleNames, smallFiles.size());
        }

        Map<String, ReviewGroup> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<FileDiff>> entry : merged.entrySet()) {
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
            int lineCount = countLines(diff.getNewContent());
            sb.append("### ").append(diff.getFilePath()).append(" (").append(changeLabel)
                    .append(") — ").append(lineCount).append(" lines\n");
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

        appendReviewChecklist(sb, false);    // diff 模式
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
    // 深度扫描：按模块分组，每模块一次 LLM 调用（打包该模块所有文件源码）
    // ================================================================

    /**
     * 深度扫描：按模块分组，每模块打包所有文件源码到一个 prompt，调一次 LLM。
     *
     * <p>与 diff 模式的 reviewDiffByModule 结构一致，区别在于：
     * <ul>
     *   <li>diff 模式打包 unified diff（仅变更行），深度扫描打包完整源码</li>
     *   <li>深度扫描对单个大文件做截断（maxSourceChars），避免超出 LLM 上下文</li>
     *   <li>prompt 中的审查指令针对全量代码（非 diff），不包含 diff 模式禁区</li>
     * </ul>
     *
     * <p>预期效果：900 文件 / 10 模块 = 10 次 LLM 调用 ≈ 2-5 分钟。</p>
     */
    List<Finding> reviewDeepScanByModule(List<FileDiff> diffs, CodeReviewContext context) throws Exception {
        // 确保每个 FileDiff 都有 newContent（从仓库读取缺失的文件）
        ensureFileContent(diffs, context.getRepoPath());
        long scanStartTime = System.currentTimeMillis();

        // ================================================================
        // Layer 0: CRG 全量构建（一次性）
        // ================================================================
        boolean crgReady = false;
        if (crgClient != null && crgClient.isEnabled()) {
            try {
                crgClient.clearQueryCache();  // 1.5 — 每次审查前清缓存
                long t0 = System.currentTimeMillis();
                // 3.4 — 用独立线程+超时保护，避免 CRG 服务挂起时阻塞主流程
                // 默认 10 分钟，线上可通过 crg.build-max-wait-seconds 调整
                crgClient.buildOrUpdateGraph(context.getRepoPath());
                long elapsed = System.currentTimeMillis() - t0;
                log.info("[CRG Layer 0] graph build/update completed in {}ms", elapsed);
                crgReady = true;
            } catch (Exception e) {
                log.warn("[CRG Layer 0] build failed, proceeding without CRG: {}", e.getMessage());
            }
        } else {
            log.info("[CRG Layer 0] CRG not enabled, skipping graph analysis");
        }

        // 基本分组（不含 CRG 上下文，后面再注入模块级摘要）
        Map<String, ReviewGroup> groups = groupByBusinessLink(diffs, context);
        int concurrency = Math.min(scanConcurrency, groups.size());
        log.info("[Deep Scan] {} files -> {} module groups, {} concurrency",
                diffs.size(), groups.size(), concurrency);

        // ================================================================
        // Layer 1: 模块级摘要 + Layer 2: 影响力过滤（并发注入）
        // 3.2 — 并行化扩充循环，消除审查前的串行瓶颈
        // ================================================================
        if (crgReady) {
            long t0 = System.currentTimeMillis();
            // 收集需要 enrich 的模块组
            List<ReviewGroup> toEnrich = new ArrayList<>();
            for (ReviewGroup group : groups.values()) {
                if (group.files.size() >= 3) {
                    toEnrich.add(group);
                } else {
                    log.info("[CRG Layer 1+2] module '{}': {} files (too small, skip enrichment)",
                            group.name, group.files.size());
                }
            }

            if (!toEnrich.isEmpty()) {
                int enrichConcurrency = Math.min(scanConcurrency, toEnrich.size());
                ExecutorService enrichExecutor = Executors.newFixedThreadPool(enrichConcurrency);
                List<CompletableFuture<Void>> enrichFutures = new ArrayList<>();
                for (ReviewGroup group : toEnrich) {
                    enrichFutures.add(CompletableFuture.runAsync(() -> {
                        long tModule = System.currentTimeMillis();
                        try {
                            enrichGroupWithModuleSummary(group);
                            long tElapsed = System.currentTimeMillis() - tModule;
                            log.info("[CRG Layer 1+2] module '{}': enrichment done in {}ms", group.name, tElapsed);
                        } catch (Exception e) {
                            log.warn("[CRG Layer 1+2] module '{}': enrichment failed: {}", group.name, e.getMessage());
                        }
                    }, enrichExecutor));
                }
                try {
                    CompletableFuture.allOf(
                            enrichFutures.toArray(new CompletableFuture[0])).join();
                } finally {
                    enrichExecutor.shutdown();
                }
            }

            long elapsed = System.currentTimeMillis() - t0;
            int enrichedCount = (int) toEnrich.stream()
                    .filter(g -> g.crgModuleSummary != null).count();
            log.info("[CRG Layer 1+2] enrichment complete: {}/{} groups enriched in {}ms",
                    enrichedCount, toEnrich.size(), elapsed);
        }

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<CompletableFuture<List<Finding>>> futures = new ArrayList<>();

        try {
            for (ReviewGroup group : groups.values()) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        long tModule = System.currentTimeMillis();
                        List<Finding> findings;

                        // --- 有 CRG 分类 → core/edge 分流 ---
                        if (group.crgClassification != null
                                && !group.crgClassification.getCoreFiles().isEmpty()) {
                            log.info("[Layer 2] module '{}': {} core + {} edge files, routing...",
                                    group.name,
                                    group.crgClassification.getCoreFiles().size(),
                                    group.crgClassification.getEdgeFiles().size());
                            findings = reviewGroupWithClassification(group, context);
                        } else {
                            // 小模块或 CRG 不可用 → 全量深审
                            log.info("[Layer 2] module '{}': no classification, full deep scan",
                                    group.name);
                            findings = reviewDeepScanModuleGroup(group, context);
                        }

                        long tElapsed = System.currentTimeMillis() - tModule;
                        log.info("[Layer 2] module '{}': {} findings in {}ms",
                                group.name, findings.size(), tElapsed);
                        return findings;
                    } catch (Exception e) {
                        log.error("[Layer 2] module '{}' failed: {}", group.name, e.getMessage(), e);
                        return Collections.<Finding>emptyList();
                    }
                }, executor));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Finding> allFindings = new ArrayList<>();
            for (CompletableFuture<List<Finding>> f : futures) {
                allFindings.addAll(f.get());
            }
            // 按 file+line+category 去重
            allFindings = deduplicateByFileAndLine(allFindings);

            long totalElapsed = System.currentTimeMillis() - scanStartTime;
            log.info("[Deep Scan] complete: {} findings from {} files in {} groups, total {}ms",
                    allFindings.size(), diffs.size(), groups.size(), totalElapsed);
            return allFindings;
        } finally {
            executor.shutdown();
        }
    }

    // ================================================================
    // Layer 1: 模块级摘要注入
    // ================================================================

    /**
     * 为 ReviewGroup 注入 CRG 模块级摘要 + 文件分类。
     *
     * <p>流程：
     * <ol>
     *   <li>调用 {@link CrgClient#getModuleSummary} 获取模块级摘要</li>
     *   <li>调用 {@link CrgClient#classifyFiles} 将文件分为 core/edge</li>
     *   <li>将摘要文本替换 group.background（替代旧的全局摘要）</li>
     * </ol>
     */
    private void enrichGroupWithModuleSummary(ReviewGroup group) {
        List<String> filePaths = group.files.stream()
                .map(FileDiff::getFilePath).collect(Collectors.toList());

        long t0 = System.currentTimeMillis();

        // Layer 1: 模块级摘要
        CrgModels.CrgModuleSummary summary = crgClient.getModuleSummary(filePaths);
        if (summary == null) {
            log.warn("[CRG Layer 1] module '{}': summary unavailable", group.name);
            return;
        }
        group.crgModuleSummary = summary;
        long t1 = System.currentTimeMillis();
        log.info("[CRG Layer 1] module '{}': {} hub methods, {} external callers, {} flows, {} community ({}ms)",
                group.name,
                summary.getTopFanInMethods() != null ? summary.getTopFanInMethods().size() : 0,
                summary.getExternalCallers() != null ? summary.getExternalCallers().size() : 0,
                summary.getModuleFlows() != null ? summary.getModuleFlows().size() : 0,
                summary.getCommunity() != null ? summary.getCommunity().getName() : "none",
                t1 - t0);

        // Layer 2: 文件分类
        CrgModels.CrgFileClassification classification = crgClient.classifyFiles(filePaths, summary);
        group.crgClassification = classification;
        long t2 = System.currentTimeMillis();
        log.info("[CRG Layer 2] module '{}': {} core / {} edge (reason: {}, {}ms)",
                group.name,
                classification.getCoreFiles().size(),
                classification.getEdgeFiles().size(),
                classification.getReason(),
                t2 - t1);

        // 1.1 — 构建 FileDiff 分类映射（路径标准化，处理 CRG 路径与 FileDiff 路径格式差异）
        Map<String, FileDiff> fileMap = new LinkedHashMap<>();
        Map<String, FileDiff> normalizedMap = new LinkedHashMap<>();  // 标准化 key
        for (FileDiff f : group.files) {
            String fp = f.getFilePath();
            fileMap.put(fp, f);
            // 用标准化路径做索引（去掉前缀、统一分隔符）
            String normalized = normalizeFilePath(fp);
            normalizedMap.put(normalized, f);
        }

        group.coreFiles = new ArrayList<>();
        for (String fp : classification.getCoreFiles()) {
            // 先精确匹配、再标准化匹配
            FileDiff diff = fileMap.get(fp);
            if (diff == null) {
                diff = normalizedMap.get(normalizeFilePath(fp));
            }
            if (diff != null) {
                group.coreFiles.add(diff);
            } else {
                log.warn("[CRG Layer 2] core file '{}' not found in fileMap (CRG-to-FileDiff path mismatch)", fp);
            }
        }

        group.edgeFiles = new ArrayList<>();
        for (String fp : classification.getEdgeFiles()) {
            FileDiff diff = fileMap.get(fp);
            if (diff == null) {
                diff = normalizedMap.get(normalizeFilePath(fp));
            }
            if (diff != null && !group.coreFiles.contains(diff)) {
                group.edgeFiles.add(diff);
            } else if (diff == null) {
                log.warn("[CRG Layer 2] edge file '{}' not found in fileMap (CRG-to-FileDiff path mismatch)", fp);
            }
        }

        // 将模块级摘要替换 group.background
        StringBuilder bg = new StringBuilder();
        bg.append("## 模块: ").append(group.name)
          .append("（").append(group.files.size()).append(" 个文件");
        if (classification.getCoreFiles().size() > 0) {
            bg.append("，核心 ").append(classification.getCoreFiles().size()).append(" 个");
        }
        bg.append("）\n");

        bg.append("### 文件列表\n");
        for (FileDiff f : group.files) {
            boolean isCore = group.coreFiles != null && group.coreFiles.contains(f);
            bg.append("- ").append(f.getFilePath())
              .append(isCore ? " [核心]" : " [边缘]")
              .append("\n");
        }
        bg.append("\n");

        // 注入模块级 CRG 摘要
        bg.append(buildCrgModuleSummaryText(summary));

        group.background = bg.toString();

        long t3 = System.currentTimeMillis();
        log.info("[CRG Layer 1+2] module '{}': enrichment complete in {}ms total (core={}, edge={})",
                group.name, t3 - t0,
                group.coreFiles != null ? group.coreFiles.size() : 0,
                group.edgeFiles != null ? group.edgeFiles.size() : 0);
    }

    // ================================================================
    // Layer 2: core/edge 分流审查
    // ================================================================

    /**
     * 按 CRG 分类分流审查：core 文件全量深审，edge 文件轻审。
     */
    private List<Finding> reviewGroupWithClassification(ReviewGroup group, CodeReviewContext context)
            throws Exception {
        List<Finding> allFindings = new ArrayList<>();

        // --- Core 文件：全量迭代 grep 深审 ---
        if (group.coreFiles != null && !group.coreFiles.isEmpty()) {
            ReviewGroup coreGroup = new ReviewGroup(
                    group.name + "#core", group.coreFiles, group.background);
            coreGroup.crgModuleSummary = group.crgModuleSummary;
            coreGroup.crgClassification = group.crgClassification;

            long t0 = System.currentTimeMillis();
            List<Finding> coreFindings = reviewDeepScanModuleGroup(coreGroup, context);
            long elapsed = System.currentTimeMillis() - t0;

            for (Finding f : coreFindings) {
                f.setModuleName(group.name);
            }
            allFindings.addAll(coreFindings);
            log.info("[Layer 2] module '{}' core: {} files -> {} findings in {}ms",
                    group.name, group.coreFiles.size(), coreFindings.size(), elapsed);
        }

        // --- Edge 文件：轻审（只发源码，不做迭代 grep） ---
        if (group.edgeFiles != null && !group.edgeFiles.isEmpty()) {
            long t0 = System.currentTimeMillis();
            List<Finding> edgeFindings = reviewEdgeFiles(group, context);
            long elapsed = System.currentTimeMillis() - t0;

            for (Finding f : edgeFindings) {
                f.setModuleName(group.name);
            }
            allFindings.addAll(edgeFindings);
            log.info("[Layer 2] module '{}' edge: {} files -> {} findings in {}ms",
                    group.name, group.edgeFiles.size(), edgeFindings.size(), elapsed);
        }

        return allFindings;
    }

    /**
     * 边缘文件轻审：只发源码做单轮审查，不做迭代 grep。
     *
     * <p>轻审策略：
     * <ol>
     *   <li>源码以简化上下文发送（只发类名 + 方法签名，不展开全部方法体）</li>
     *   <li>不发迭代 grep 能力，LLM 必须在单轮内完成审查</li>
     *   <li>只关注 P0/P1 级别问题（安全、事务、NPE、并发、资源泄漏）</li>
     * </ol>
     */
    private List<Finding> reviewEdgeFiles(ReviewGroup parentGroup, CodeReviewContext context)
            throws Exception {
        if (parentGroup.edgeFiles == null || parentGroup.edgeFiles.isEmpty()) {
            return Collections.emptyList();
        }

        List<FileDiff> edgeFiles = parentGroup.edgeFiles;

        // 构建轻审 prompt：只发轻量上下文
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是资深 Java 代码审查专家，请快速审查以下边缘文件的代码。\n\n");
        prompt.append("## 审查范围\n");
        prompt.append("这些文件被 CRG 分析标记为低风险（不属于核心调用圈），");
        prompt.append("请只关注 P0/P1 级别的严重问题：\n");
        prompt.append("- SECURITY: SQL注入、XSS、权限绕过\n");
        prompt.append("- TRANSACTION: 写操作缺少事务\n");
        prompt.append("- NPE: 明显的空指针风险\n");
        prompt.append("- CONCURRENCY: 共享可变变量线程安全\n");
        prompt.append("- RESOURCE_LEAK: 资源未关闭\n\n");
        prompt.append("不要报告 P2/P3 级别的问题（性能、代码风格、硬编码、死代码等）。\n\n");

        // 只发送文件概要 + 截断的源码
        prompt.append("## 模块: ").append(parentGroup.name).append("（边缘 ")
              .append(edgeFiles.size()).append(" 个文件）\n\n");
        for (FileDiff diff : edgeFiles) {
            String content = diff.getNewContent();
            if (content == null || content.isEmpty()) continue;
            // 轻审发送更短的截断（6000 chars vs 12000）
            if (content.length() > 6000) {
                content = content.substring(0, 6000)
                        + "\n... [truncated, total " + diff.getNewContent().length() + " chars]";
            }
            prompt.append("### ").append(diff.getFilePath()).append("\n");
            prompt.append("```java\n").append(content).append("\n```\n\n");
        }

        // 如果有模块摘要，简要注入
        if (parentGroup.crgModuleSummary != null) {
            prompt.append("## 代码结构参考\n");
            CrgModels.CrgModuleSummary s = parentGroup.crgModuleSummary;
            if (s.getExternalCallers() != null && !s.getExternalCallers().isEmpty()) {
                prompt.append("此模块被外部调用：");
                for (CrgModels.CrgExternalCaller ec : s.getExternalCallers()) {
                    prompt.append(ec.getCaller()).append(" → ").append(ec.getCallee()).append(", ");
                }
                prompt.append("\n");
            }
            if (s.getModuleFlows() != null && !s.getModuleFlows().isEmpty()) {
                prompt.append("涉及关键流：");
                for (CrgModels.CrgFlowSummary flow : s.getModuleFlows()) {
                    prompt.append(flow.getName()).append(", ");
                }
                prompt.append("\n");
            }
        }

        prompt.append("\n请输出 JSON（不要 markdown 代码块标记）：\n");
        prompt.append("{\"findings\": [{\"file\": \"...\", \"severity\": \"HIGH\", "
                + "\"category\": \"NPE\", \"evidence\": \"...\",\"suggestedFix\": \"...\", "
                + "\"trigger\": \"...\", \"startLine\": 45, \"endLine\": 45, \"confidence\": 0.80, "
                + "\"symbol\": \"关联方法名，如 UserService.save（可选）\"}]}\n");
        prompt.append("如果没有发现问题，输出 {\"findings\": []}\n");

        // 单轮 LLM 调用（无迭代 grep）
        String response;
        try {
            String provider = getProvider();
            String apiKey = getApiKey();
            String apiUrl = getApiUrl();
            String model = getModel();
            response = llmClient.call(provider, apiKey, apiUrl, model,
                    prompt.toString(), 4096, "json_object");
        } catch (Exception e) {
            log.warn("[Layer 2 edge] LLM call failed for module '{}': {}", parentGroup.name, e.getMessage());
            return Collections.emptyList();
        }

        // 解析响应
        try {
            String json = extractJson(response);
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(json);
            com.fasterxml.jackson.databind.JsonNode findingsNode = root.get("findings");
            if (findingsNode != null && findingsNode.isArray()) {
                List<Finding> findings = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode fn : findingsNode) {
                    Finding f = MAPPER.treeToValue(fn, Finding.class);
                    if (f.getConfidence() <= 0) f.setConfidence(0.70);
                    findings.add(f);
                }
                log.info("[Layer 2 edge] {} files -> {} findings (light scan)",
                        edgeFiles.size(), findings.size());
                return findings;
            }
        } catch (Exception e) {
            log.warn("[Layer 2 edge] failed to parse light scan response: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 确保所有 FileDiff 都有 newContent。缺失的从仓库路径读取。
     */
    private void ensureFileContent(List<FileDiff> diffs, String repoPath) {
        for (FileDiff diff : diffs) {
            if (diff.getNewContent() == null || diff.getNewContent().isEmpty()) {
                try {
                    java.nio.file.Path fullPath = java.nio.file.Paths.get(repoPath, diff.getFilePath());
                    String content = new String(java.nio.file.Files.readAllBytes(fullPath),
                            java.nio.charset.StandardCharsets.UTF_8);
                    diff.setNewContent(content);
                } catch (Exception e) {
                    log.warn("Cannot read file {} from repo: {}", diff.getFilePath(), e.getMessage());
                    diff.setNewContent(""); // 标记为空，后续跳过
                }
            }
        }
    }

    /**
     * 对一个模块组的所有文件调 LLM 审查（深度扫描模式）。
     * 文件总量 <= maxChunkChars → 单次调用（方案A，质量最好）。
     * 文件总量 > maxChunkChars → 拆块 + 共享摘要 + 并行调用（方案D）。
     */
    List<Finding> reviewDeepScanModuleGroup(ReviewGroup group, CodeReviewContext context) throws Exception {
        int totalChars = 0;
        for (FileDiff f : group.files) {
            String content = f.getNewContent();
            if (content != null) totalChars += Math.min(content.length(), maxSourceChars);
        }

        List<Finding> findings;
        if (totalChars <= maxChunkChars) {
            // 方案A：迭代式 grep 追踪审查
            findings = reviewDeepScanModuleIterative(group, context);
        } else {
            // 方案D：拆块 + 共享摘要 + 并行迭代 grep
            log.info("Deep scan module '{}': {} chars > {} limit, using parallel chunks with iterative grep",
                    group.name, totalChars, maxChunkChars);
            findings = reviewDeepScanWithSummary(group, context);
        }

        log.info("Deep scan module '{}': {} findings from {} files",
                group.name, findings.size(), group.files.size());
        return findings;
    }

    /**
     * 方案D：拆块 + 共享摘要 + 并行迭代 grep。
     * 每个块独立走迭代式 grep 追踪审查，最后合并 findings。
     * 每个块的背景包含：架构上下文 + 其他块文件的类/方法签名摘要。
     */
    private List<Finding> reviewDeepScanWithSummary(ReviewGroup group, CodeReviewContext context) throws Exception {
        List<List<FileDiff>> chunks = splitFilesIntoChunks(group.files, maxChunkChars);
        // 构建所有文件的签名摘要（类名 + 方法名），排除当前块的文件
        Map<List<FileDiff>, String> signatureCache = new LinkedHashMap<>();
        for (List<FileDiff> chunk : chunks) {
            signatureCache.put(chunk, buildSignatureSummaryExcluding(group.files, chunk));
        }
        // 架构上下文（与方案A 共享）
        String graphBg = buildGraphBackground(context);

        int concurrency = Math.min(scanConcurrency, chunks.size());
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<CompletableFuture<List<Finding>>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < chunks.size(); i++) {
                final int chunkIdx = i;
                final List<FileDiff> chunk = chunks.get(i);
                final String otherSignatures = signatureCache.get(chunk);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        String chunkBg = graphBg;
                        if (otherSignatures != null && !otherSignatures.isEmpty()) {
                            chunkBg += "\n\n## 本模块其他文件的类与方法概览\n\n" + otherSignatures
                                    + "\n请关注本批代码与上述其他文件的调用关系，重点排查运行时隐患（空指针、并发安全、资源泄漏、异常处理等），忽略编译级问题。\n";
                        }
                        ReviewGroup chunkGroup = new ReviewGroup(
                                group.name + "#" + chunkIdx, chunk, chunkBg);
                        List<Finding> findings = reviewDeepScanModuleIterative(chunkGroup, context);
                        for (Finding f : findings) {
                            f.setModuleName(group.name);
                        }
                        return findings;
                    } catch (Exception e) {
                        log.error("Deep scan module '{}' chunk {}/{} failed: {}",
                                group.name, chunkIdx + 1, chunks.size(), e.getMessage());
                        return Collections.<Finding>emptyList();
                    }
                }, executor));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Finding> allFindings = new ArrayList<>();
            for (CompletableFuture<List<Finding>> f : futures) {
                allFindings.addAll(f.get());
            }
            return allFindings;
        } finally {
            executor.shutdown();  // 1.3 — 确保 join 异常时也关闭执行器
        }
    }

    /** 从 LLM JSON 响应解析 Finding 列表 */
    private List<Finding> parseFindings(String label, String response) {
        String json = extractJson(response);
        if (json.isEmpty()) {
            log.warn("Deep scan '{}': LLM returned no valid JSON, raw={}", label,
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);
            return Collections.emptyList();
        }
        OcrReviewResponse ocrResult = parseOcrResult(label, json);
        if (ocrResult == null) return Collections.emptyList();

        List<Finding> findings = new ArrayList<>();
        List<OcrComment> comments = ocrResult.getComments();
        if (comments != null) {
            for (OcrComment cm : comments) {
                findings.add(Finding.fromOcrComment(cm));
            }
        }
        return findings;
    }

    /**
     * 将模块文件按字符数分块。每个块的源码总字符数不超过 maxChunkChars。
     */
    private List<List<FileDiff>> splitFilesIntoChunks(List<FileDiff> files, int maxChars) {
        List<List<FileDiff>> chunks = new ArrayList<>();
        List<FileDiff> currentChunk = new ArrayList<>();
        int currentSize = 0;

        for (FileDiff f : files) {
            int fileSize = f.getNewContent() != null ? Math.min(f.getNewContent().length(), maxSourceChars) : 0;
            if (currentSize + fileSize > maxChars && !currentChunk.isEmpty()) {
                chunks.add(currentChunk);
                currentChunk = new ArrayList<>();
                currentSize = 0;
            }
            currentChunk.add(f);
            currentSize += fileSize;
        }
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk);
        }
        return chunks;
    }

    /**
     * 构建签名摘要，排除指定块中的文件（避免本块文件重复出现在摘要中）。
     */
    private String buildSignatureSummaryExcluding(List<FileDiff> allFiles, List<FileDiff> excludeChunk) {
        Set<String> excludePaths = new LinkedHashSet<>();
        for (FileDiff f : excludeChunk) {
            excludePaths.add(f.getFilePath());
        }

        StringBuilder sb = new StringBuilder();
        for (FileDiff diff : allFiles) {
            if (excludePaths.contains(diff.getFilePath())) continue;
            String content = diff.getNewContent();
            if (content == null || content.isEmpty()) continue;
            JavaFileParser parser = new JavaFileParser(content);
            if (parser.getClassNames().isEmpty()) continue;

            sb.append("- ").append(diff.getFilePath()).append(": ");
            sb.append(String.join(", ", parser.getClassNames()));
            if (!parser.getMethodNames().isEmpty()) {
                sb.append(" → ").append(String.join(", ", parser.getMethodNames()));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 构建带共享摘要的分块 prompt。
     * 包含：项目信息 + 本块文件完整源码 + 其他块文件的签名摘要 + 审查清单。
     */
    private String buildDeepScanChunkWithSummary(String moduleName, List<FileDiff> chunk,
                                                   int chunkIdx, int totalChunks,
                                                   String otherSignatures, String graphBg,
                                                   CodeReviewContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个资深的 Java 代码审查专家。请审查以下代码。\n\n");

        sb.append("## 项目信息\n");
        sb.append("- 项目：").append(context.getProjectName() != null ? context.getProjectName() : "unknown").append("\n");
        sb.append("- 分支：").append(context.getBranch() != null ? context.getBranch() : "unknown").append("\n\n");

        sb.append("## 模块: ").append(moduleName).append("（审查第 ").append(chunkIdx + 1)
                .append("/").append(totalChunks).append(" 批，共 ").append(chunk.size()).append(" 个文件）\n\n");

        // 本批文件完整源码
        for (FileDiff diff : chunk) {
            int lineCount = countLines(diff.getNewContent());
            sb.append("### ").append(diff.getFilePath()).append(" (").append(lineCount).append(" lines)\n");
            String content = diff.getNewContent();
            if (content != null && !content.isEmpty()) {
                if (content.length() > maxSourceChars) {
                    content = content.substring(0, maxSourceChars)
                            + "\n... [truncated, total " + diff.getNewContent().length() + " chars]";
                }
                sb.append("```java\n").append(content).append("\n```\n\n");
            }
        }

        // 架构上下文（与方案A 一致）
        if (graphBg != null && !graphBg.isEmpty()) {
            sb.append("## 架构上下文\n").append(graphBg).append("\n\n");
        }

        // 共享摘要：本模块其他文件的类/方法签名
        if (otherSignatures != null && !otherSignatures.isEmpty()) {
            sb.append("## 本模块其他文件的类与方法概览（供跨文件引用检查）\n\n");
            sb.append(otherSignatures).append("\n");
            sb.append("请关注本批代码是否与上述其他文件存在调用关系、接口不匹配、字段名不一致等问题。\n\n");
        }

        // 审查清单
        appendReviewChecklist(sb, false);

        return sb.toString();
    }

    // ================================================================
    // 深度扫描 prompt 构建（单次调用 + 分块通用）
    // ================================================================

    /**
     * 构建深度扫描的模块级 prompt（单次调用，方案A）。
     */
    private String buildDeepScanPrompt(ReviewGroup group, CodeReviewContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个资深的 Java 代码审查专家。请审查以下模块的所有文件代码。\n\n");

        sb.append("## 项目信息\n");
        sb.append("- 项目：").append(context.getProjectName() != null ? context.getProjectName() : "unknown").append("\n");
        sb.append("- 分支：").append(context.getBranch() != null ? context.getBranch() : "unknown").append("\n\n");

        sb.append("## 模块: ").append(group.name).append("（共 ").append(group.files.size()).append(" 个文件）\n\n");

        for (FileDiff diff : group.files) {
            int lineCount = countLines(diff.getNewContent());
            sb.append("### ").append(diff.getFilePath()).append(" (").append(lineCount).append(" lines)\n");
            String content = diff.getNewContent();
            if (content != null && !content.isEmpty()) {
                if (content.length() > maxSourceChars) {
                    content = content.substring(0, maxSourceChars)
                            + "\n... [truncated, total " + diff.getNewContent().length() + " chars]";
                }
                sb.append("```java\n").append(content).append("\n```\n\n");
            } else {
                sb.append("（文件内容为空或无法读取）\n\n");
            }
        }

        if (group.background != null && !group.background.isEmpty()) {
            sb.append("## 架构上下文\n").append(group.background).append("\n\n");
        }

        appendReviewChecklist(sb, true);
        return sb.toString();
    }

    /** 追加审查清单（diff/全量共用结构，模式特定部分通过 isFullScan 区分） */
    private void appendReviewChecklist(StringBuilder sb, boolean isFullScan) {
        // ===== S1: 最高优先级 — 不要报告什么 =====
        sb.append("## 审查要求\n\n");
        sb.append("### ⛔ 不要报告的情况（最高优先级，优先于下方审查清单）\n\n");

        if (isFullScan) {
            // 全量扫描模式
            sb.append("**全量模式上下文**：你能看到本模块全部文件的完整源码，可做跨文件调用分析。");
            sb.append("但你**看不到其他模块的代码**——@Autowired 的 bean、import 的类在其他模块定义是正常的，不要报不存在。\n\n");
        } else {
            // Diff 增量模式
            sb.append("**Diff 模式上下文**：你**只能看到变更文件**，未变更的代码对你不可见。\n");
            sb.append("- 看到 @Autowired 的 bean 在 diff 中找不到定义 → 不代表不存在，不要报\n");
            sb.append("- 看到 import 了某个类但在 diff 中看不到 → 正常，不要报\n");
            sb.append("- 看到 @Async(\"xxx\") 但 diff 中没有 Executor bean → 大概率在其他文件定义了，不要报\n");
            sb.append("- **版本号变了不代表有问题**——只有看到新版本 API 确实与调用代码不兼容时才能报\n\n");
        }

        sb.append("**通用规则**（两种模式都适用）：\n");
        sb.append("- 代码能构建部署说明无编译错误 → 不要报类型不匹配/签名不对/缺引号/注解误用等\n");
        sb.append("- 心想「可能」「也许」「建议检查」→ 直接跳过，只有代码中能确证的问题才报\n");
        sb.append("- 自我否认（写了「不会抛异常」「实际不会有问题」）→ 立刻删除此 finding\n");
        sb.append("- 不会产生 bug 的不规范写法 → CODE_STYLE + P3，不要归为 NPE/LOGIC_ERROR\n\n");

        // ===== S2: 分类优先级 =====
        sb.append("### 分类优先级\n");
        sb.append("`SECRET_EXPOSURE > SECURITY > TRANSACTION > CONCURRENCY > NPE > RESOURCE_LEAK > ERROR_HANDLING > ARCHITECTURE > LOGIC_ERROR > PERFORMANCE > DEPENDENCY > HARDCODED > DEAD_CODE > CODE_STYLE > OTHER`\n");
        sb.append("举例：@DS 放在接口而非实现类导致不生效 → LOGIC_ERROR（不是 CODE_STYLE）；硬编码密码 → SECRET_EXPOSURE（不是 HARDCODED）\n\n");

        // ===== S3: 审查清单 =====
        sb.append("### 审查清单\n\n");
        sb.append("**P0 阻断**：\n");
        sb.append("- SECURITY：SQL注入（${}拼接）、XSS、权限绕过、反序列化、SSRF、路径穿越、XXE\n");
        sb.append("- SECRET_EXPOSURE：Java 代码中硬编码密码/Token/密钥（配置文件中的不算）\n");
        sb.append("- TRANSACTION：写操作缺少 @Transactional\n\n");

        sb.append("**P1 高危**：\n");
        sb.append("- NPE：返回值/参数/集合元素未判空；Optional.get() 无保护\n");
        sb.append("- CONCURRENCY：共享可变变量无线程安全；HashMap 误用于多线程\n");
        sb.append("- RESOURCE_LEAK：Stream/Connection/IO 未在 try-with-resources 关闭\n");
        sb.append("- ERROR_HANDLING：空 catch、吞异常、finally 中有 return\n");
        sb.append("- ARCHITECTURE：循环依赖、Controller 直接调 DAO\n");
        sb.append("- LOGIC_ERROR：条件/计算错误、边界值未处理、注解放错位置不生效（@DS/@Transactional/@Cacheable 在接口、@Async 无代理调用）\n\n");

        sb.append("**P2 中危**：\n");
        sb.append("- PERFORMANCE：循环内 DB 调用；字符串 + 拼接\n");
        sb.append("- DEPENDENCY：SNAPSHOT/过期版本\n\n");

        sb.append("**P3 低危**：\n");
        sb.append("- HARDCODED：魔法数字、写死配置\n");
        sb.append("- CODE_STYLE：命名不规范、GET 执行写操作、不规范但不产生 bug\n");
        sb.append("- DEAD_CODE：未使用的变量/方法、永不执行的分支\n\n");

        // ===== S4: 严重度校准 =====
        sb.append("### 严重度校准\n");
        sb.append("- GET 执行写操作 → CODE_STYLE + P3，除非有实际注入/越权\n");
        sb.append("- 字段有默认值（`Boolean x = true`）→ 不要报 NPE\n");
        sb.append("- pom.xml/properties/yml → 不要报（部署配置问题）\n\n");

        // ===== S5: evidence =====
        sb.append("### evidence 要求\n");
        sb.append("- 从源码**复制原文**，禁止用文字描述代替\n");
        sb.append("- 正确：`String sql = \"DELETE FROM \" + table;`　错误：「拼接了用户输入的表名」\n");
        sb.append(isFullScan
                ? "- 从上面的 ```java 代码块中复制，不要自己编造\n\n"
                : "- 从上面的 ```diff 代码块中复制，不要自己编造\n\n");

        // ===== S6: 置信度 =====
        sb.append("### 置信度\n");
        sb.append("- 确证 → 0.85-0.99；不确定 → ≤0.70 或直接跳过\n\n");

        // ===== S7: 输出格式 + 要求 =====
        sb.append("### 输出格式\n");
        sb.append("直接输出 JSON（不要 ```json 包裹）：\n");
        sb.append("{\"comments\": [{\"path\": \"...\", \"category\": \"NPE\", \"content\": \"...\", \"existingCode\": \"...\", \"suggestionCode\": \"...\", \"startLine\": ");
        sb.append(isFullScan ? "源文件行号" : "diff +c侧行号");
        sb.append(", \"endLine\": 行号, \"thinking\": \"...\"}]}\n");
        sb.append("没有问题则 {\"comments\": []}\n");
        sb.append("category 取值：SECURITY, NPE, TRANSACTION, CONCURRENCY, RESOURCE_LEAK, ERROR_HANDLING, SECRET_EXPOSURE, CODE_STYLE, PERFORMANCE, DEPENDENCY, ARCHITECTURE, LOGIC_ERROR, HARDCODED, DEAD_CODE, OTHER\n");
        sb.append("每个 comment 必须包含 path、category、content、startLine、endLine 字段。只输出 JSON，不要有其他文字。\n");
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
     *
     * <p>B1 修复 — 按模式分流：
     * <ul>
     *   <li>diff 模式 → 现有 ImpactScope 逻辑（保留不动）</li>
     *   <li>deep scan 模式 → CRG 全局摘要（高扇入方法 + 执行流 + 社区）</li>
     * </ul>
     */
    private String buildGraphBackground(CodeReviewContext context) {

        // === deep scan 模式：模块级摘要由 enrichGroupWithModuleSummary() 注入 ===
        // 这里只返回最小兜底文本，后续会被替换
        if (context.isUseOcrDeepScan()) {
            log.debug("buildGraphBackground: deep scan mode, module-level summary will be injected later");
            return "请全面审查代码缺陷：空指针、事务边界、并发安全、异常处理、资源释放。";
        }

        // === diff 模式：走现有 ImpactScope 逻辑（保留不动） ===
        return buildGraphBackgroundFromImpactScope(context);
    }

    /**
     * CRG 模块级摘要 → 审查背景文本（~500 tokens/模块）。
     * 与全局摘要不同，这里只包含本模块相关的数据。
     */
    private String buildCrgModuleSummaryText(CrgModels.CrgModuleSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 代码结构分析（CRG 模块级）\n\n");

        // 模块统计
        sb.append("本模块 ").append(summary.getTotalFiles()).append(" 个文件");
        if (summary.getHighImpactFileCount() > 0) {
            sb.append("，其中 ").append(summary.getHighImpactFileCount()).append(" 个高风险文件");
        }
        sb.append("。\n");

        // 高扇入方法（本模块内）
        List<CrgModels.CrgFanInMethod> fanInMethods = summary.getTopFanInMethods();
        if (fanInMethods != null && !fanInMethods.isEmpty()) {
            sb.append("\n### 高风险方法（本模块内，高扇入）\n");
            sb.append("以下方法被多处调用，修改影响面大，优先审查：\n");
            for (CrgModels.CrgFanInMethod m : fanInMethods) {
                sb.append("- ").append(m.getName())
                  .append(" — ").append(m.getCallers()).append(" 处调用");
                if (m.getFile() != null) {
                    sb.append(" (").append(m.getFile());
                    if (m.getLine() != null) {
                        sb.append(":").append(m.getLine());
                    }
                    sb.append(")");
                }
                sb.append("\n");
            }
        }

        // 外部调用者（谁从模块外调本模块）
        List<CrgModels.CrgExternalCaller> externalCallers = summary.getExternalCallers();
        if (externalCallers != null && !externalCallers.isEmpty()) {
            sb.append("\n### 外部调用者（模块外 → 本模块）\n");
            sb.append("以下方法被外部模块调用，是模块的入口点，改动影响面大：\n");
            for (CrgModels.CrgExternalCaller ec : externalCallers) {
                sb.append("- ").append(ec.getCaller())
                  .append("\n  → ").append(ec.getCallee());
                if (ec.getCalleeFile() != null) {
                    sb.append(" (").append(ec.getCalleeFile()).append(")");
                }
                sb.append("\n");
            }
        }

        // 执行流（流经本模块的）
        List<CrgModels.CrgFlowSummary> flows = summary.getModuleFlows();
        if (flows != null && !flows.isEmpty()) {
            sb.append("\n### 流经本模块的关键执行流\n");
            sb.append("关注流程中的异常处理、事务一致性：\n");
            for (CrgModels.CrgFlowSummary flow : flows) {
                sb.append("- ").append(flow.getName())
                  .append(" (criticality=").append(String.format("%.2f", flow.getCriticality()))
                  .append(", depth=").append(flow.getDepth())
                  .append(", ").append(flow.getNodeCount()).append(" nodes)\n");
                List<String> steps = flow.getPathSteps();
                if (steps != null) {
                    for (String step : steps) {
                        sb.append("  → ").append(step).append("\n");
                    }
                }
            }
        }

        // 社区归属
        CrgModels.CrgCommunitySummary community = summary.getCommunity();
        if (community != null) {
            sb.append("\n### 模块社区归属\n");
            sb.append("- ").append(community.getName())
              .append(": ").append(community.getSize()).append(" nodes")
              .append(", cohesion=").append(String.format("%.2f", community.getCohesion()));
            if (community.getDominantLanguage() != null) {
                sb.append(", lang=").append(community.getDominantLanguage());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 从 ImpactScope 提取架构背景（diff 模式 + deep scan fallback）。
     * W4 修复 — 每个 catch 分支都加 WARN 日志。
     */
    private String buildGraphBackgroundFromImpactScope(CodeReviewContext context) {
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
                log.warn("Failed to parse graphAnalysisJson, falling back to context.getGraph(): {}", e.getMessage());
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
            log.warn("ImpactScope not available for {} mode", context.isUseOcrDeepScan() ? "deep scan" : "diff");
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

    /** 1.1 — 标准化文件路径（统一分隔符、去掉前缀差异），用于 CRG-to-FileDiff 匹配 */
    private static String normalizeFilePath(String path) {
        if (path == null) return "";
        return path.replace('\\', '/');
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

    /** 统计文本行数 */
    private int countLines(String content) {
        if (content == null || content.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') count++;
        }
        return count;
    }

    // ================================================================
    // 迭代式 Grep 追踪深度扫描
    // ================================================================

    /**
     * 迭代式深度扫描：LLM 自主决定 grep 追踪，逐轮确认 findings。
     *
     * <p>流程：
     * <ol>
     *   <li>Round 1: 模块源码 → LLM → { confirmed, grep_requests, done }</li>
     *   <li>Round 2+: 批量 grep 结果 → LLM → { confirmed, grep_requests, done }</li>
     *   <li>每轮 confirmed findings 立即存入结果列表（本地缓存）</li>
     *   <li>下一轮只发 grep 结果，LLM 从对话历史看到之前已确认的</li>
     *   <li>循环直到 done=true 或达到 maxGrepRounds</li>
     * </ol>
     */
    private List<Finding> reviewDeepScanModuleIterative(ReviewGroup group,
                                                         CodeReviewContext context) throws Exception {
        String repoPath = context.getRepoPath();
        List<Finding> allConfirmed = new ArrayList<>();

        // 构建 system prompt（固定）
        String systemPrompt = buildIterativeSystemPrompt();
        // 构建第一轮 user message（模块源码）
        String userMessage = buildIterativeFirstRoundMessage(group, context);

        // 多轮对话消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        String provider = getProvider();
        String apiKey = getApiKey();
        String apiUrl = getApiUrl();
        String model = getModel();

        for (int round = 1; round <= maxGrepRounds; round++) {
            log.info("Module '{}' iterative round {}: {} confirmed so far",
                    group.name, round, allConfirmed.size());

            // 添加 user message
            Map<String, Object> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.add(userMsg);

            // 估算对话总字符数
            int totalChars = 0;
            for (Map<String, Object> msg : messages) {
                Object content = msg.get("content");
                if (content != null) totalChars += content.toString().length();
            }
            log.info("Module '{}' round {}: calling LLM ({} messages, ~{} chars)",
                    group.name, round, messages.size(), totalChars);

            // 调 LLM（多轮对话）
            String response = llmClient.callMultiTurn(provider, apiKey, apiUrl, model,
                    messages, 8192, 0.1, "json_object");

            log.info("Module '{}' round {}: LLM responded {} chars", group.name, round,
                    response != null ? response.length() : 0);

            // 添加 assistant message 到对话历史
            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", response);
            messages.add(assistantMsg);

            // 解析结果（格式错误时自动重试）
            IterativeReviewResult result = parseWithRetry(messages, provider, apiKey, apiUrl, model, response);
            if (result == null) break;

            // 存储本轮确认的 findings
            if (result.getConfirmed() != null && !result.getConfirmed().isEmpty()) {
                for (Finding f : result.getConfirmed()) {
                    f.setModuleName(group.name);
                }
                allConfirmed.addAll(result.getConfirmed());
                log.info("Module '{}' round {}: {} findings confirmed",
                        group.name, round, result.getConfirmed().size());
            }

            // done=true 或没有 grep 请求 → 结束
            if (result.isDone() || result.getGrepRequests() == null || result.getGrepRequests().isEmpty()) {
                log.info("Module '{}': done after {} rounds, {} total findings",
                        group.name, round, allConfirmed.size());
                break;
            }

            // 超过轮次限制 → 日志告警，丢弃
            if (round >= maxGrepRounds) {
                log.warn("Module '{}': reached max {} rounds, discarding {} remaining grep requests: {}",
                        group.name, maxGrepRounds, result.getGrepRequests().size(),
                        result.getGrepRequests().stream().map(GrepRequest::getSymbol).collect(Collectors.joining(", ")));
                break;
            }

            // 限制本轮 grep 数量
            List<GrepRequest> requests = result.getGrepRequests();
            if (requests.size() > maxGrepPerRound) {
                log.warn("Module '{}': {} grep requests exceeds limit {}, truncating",
                        group.name, requests.size(), maxGrepPerRound);
                requests = requests.subList(0, maxGrepPerRound);
            }

            // 执行 grep，构建下一轮 user message
            String grepResults = grepTracer.executeRequests(repoPath, requests);
            int remaining = maxGrepRounds - round - 1;
            userMessage = buildGrepRoundMessage(grepResults, remaining);
        }

        log.info("Module '{}': iterative scan complete, {} total findings",
                group.name, allConfirmed.size());
        return allConfirmed;
    }

    /**
     * 构建迭代审查的 system prompt（固定，不随轮次变化）。
     * 包含完整的审查清单、输出格式、置信度规则。
     */
    private String buildIterativeSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深 Java 代码审查专家。请逐文件逐行审查代码，输出发现的问题。\n\n");

        // 输出格式
        sb.append("输出严格 JSON（不要 markdown 代码块标记）：\n");
        sb.append("{\n");
        sb.append("  \"confirmed\": [\n");
        sb.append("    {\n");
        sb.append("      \"file\": \"文件路径\",\n");
        sb.append("      \"severity\": \"HIGH\",\n");
        sb.append("      \"category\": \"NPE\",\n");
        sb.append("      \"evidence\": \"必须是从源码复制的实际代码，例如: String sql = \\\"SELECT * FROM \\\" + table + \\\" WHERE \\\" + whereSql;\",\n");
        sb.append("      \"suggestedFix\": \"修复建议代码\",\n");
        sb.append("      \"trigger\": \"触发条件\",\n");
        sb.append("      \"startLine\": 45,\n");
        sb.append("      \"endLine\": 45,\n");
        sb.append("      \"confidence\": 0.90,\n");
        sb.append("      \"symbol\": \"关联方法名，如 UserService.save（可选，填写后系统会做调用链验证）\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"grep_requests\": [\n");
        sb.append("    {\n");
        sb.append("      \"symbol\": \"类名.方法名\",\n");
        sb.append("      \"file\": \"文件路径（可选，有则直接读取该文件的方法体）\",\n");
        sb.append("      \"reason\": \"为什么要看这段代码\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"done\": false\n");
        sb.append("}\n\n");

        // grep 使用规则
        sb.append("## grep 使用规则\n");
        sb.append("- 如果你发现问题可疑但不确定（例如：不确定某个值是否可被外部控制、不确定某个方法是否有副作用），\n");
        sb.append("  **必须**先通过 grep_requests 查看相关代码再下结论，不要在 confirmed 中输出猜测性判断\n");
        sb.append("- grep_requests 输出你需要查看外部代码才能确认的可疑点\n");
        sb.append("  - symbol：类名.方法名（如 UserRepository.findById），所有重载版本都会返回\n");
        sb.append("  - file：可选，如果知道文件路径填写后会直接读取该文件的方法体（含注解和 JavaDoc）\n");
        sb.append("  - 无 file 时会全局 grep 搜索该符号\n");
        sb.append("  - 每轮最多 ").append(maxGrepPerRound).append(" 个 grep_requests\n");
        sb.append("- done=true 表示审查完毕，不需要更多上下文\n");
        sb.append("- 如果你确定没有问题，confirmed 输出空数组，done=true\n");
        sb.append("- **每个 finding 只报告一个问题**，不要用 --- 或换行合并多个问题到一个 finding 中\n\n");

        // 必须 grep 硬规则
        sb.append("## 必须 grep 硬规则（违反则该 finding 作废）\n");
        sb.append("以下类型的问题，如果你没有通过 grep 看到方法的**完整实现**（包括循环体、后续操作、方法注解），\n");
        sb.append("**禁止**直接报到 confirmed，必须先用 grep_requests 确认：\n");
        sb.append("1. **事务缺失** — 必须先确认：\n");
        sb.append("   - 该方法不是框架内置方法（MyBatis-Plus 的 removeByIds/saveBatch/updateBatchById/removeBatchByIds 底层是单次SQL，不需要事务）\n");
        sb.append("   - 循环体内的每一步操作确实需要在同一事务中（逐行处理+catch的批处理模式不应包事务）\n");
        sb.append("2. **并发问题** — 必须先确认：\n");
        sb.append("   - 变量是共享可变的（类字段/static），而不是方法内局部变量\n");
        sb.append("   - 确实存在多线程同时访问的场景\n");
        sb.append("3. **资源泄漏** — 必须先确认：\n");
        sb.append("   - 没有 try-with-resources 或框架自动关闭机制（如 Hutool HttpRequest 自动管理连接）\n");
        sb.append("   - 资源确实被创建后未关闭\n");
        sb.append("如果你无法 grep 确认（如超出轮次限制），该 finding 的 confidence 必须 ≤ 0.60\n\n");

        // 全量模式注意事项
        sb.append("## 全量扫描模式说明\n");
        sb.append("- 你看到的是模块内文件的完整源码（非 diff），请基于完整代码上下文审查\n");
        sb.append("- 你可以看到本模块内所有文件，跨文件调用关系可以完整分析\n");
        sb.append("- 你**看不到其他模块的代码** — 如果 @Autowired 的 bean 定义在其他模块，不要报「bean 不存在」\n");
        sb.append("- 如果 import 了其他模块的类但看不到其源码，这是正常的，不要报「类不存在」\n");
        sb.append("- **严禁报告「依赖版本升级的风险」除非你确认真的不兼容** — 版本号变了不代表有问题\n");
        sb.append("- **行号必须是源文件的原始行号**，文件可能被截断，但行号从第 1 行开始计数，不要从截断点重新计数\n");
        sb.append("- **审查代码时必须检查所在方法/类的注解**（如 @Transactional、@Async、@Cacheable、@Scheduled 等），不要只看代码片段本身\n\n");

        // B3 修复 — CRG 使用指南
        sb.append("## 代码结构分析（CRG）\n");
        sb.append("你已收到本模块的代码结构分析（见用户消息中的「代码结构分析（CRG）」章节），包含：\n");
        sb.append("- **高风险方法列表**（高扇入）：这些方法被多处调用，修改影响面大，优先审查\n");
        sb.append("- **关键执行流**：流经本模块的业务流程，关注流程中的异常处理、事务一致性\n");
        sb.append("- **模块社区**：代码的模块归属和耦合度，高耦合区域需要更严格的审查\n\n");
        sb.append("**当你需要查看某个方法的调用关系时**，发出 grep 请求（symbol 填 ClassName.methodName），\n");
        sb.append("系统会返回精确的调用链（调用者 + 被调用者 + 方法体），而非纯文本匹配。\n");
        sb.append("这比传统的 grep 高效得多，通常 1-2 轮就能确认一个 finding。\n\n");
        sb.append("**利用 CRG 数据的策略**：\n");
        sb.append("1. 先看高风险方法列表，优先审查这些方法的实现\n");
        sb.append("2. 对可疑方法发 grep 请求，系统会返回调用链，帮你判断影响范围\n");
        sb.append("3. 如果发现跨模块调用，重点关注事务一致性和异常传播\n\n");

        // 编译错误 + 不要报告规则（最高优先级）
        sb.append("## ⛔ 不要报告的情况\n");
        sb.append("- 跨模块引用看不到 → 正常，不要报「类/Bean/方法不存在」\n");
        sb.append("- 类型不匹配/签名不对/缺引号 → 编译错误，能部署说明不存在\n");
        sb.append("- pom.xml/properties/yml → 不要报（部署配置问题）\n");
        sb.append("- 不确定 → 用 grep_requests 确认后再报，不要猜测\n");
        sb.append("- 自我否认（写了「不会抛异常」「实际不会有问题」）→ 立刻删除\n");
        sb.append("- 不规范但不会产生 bug → CODE_STYLE + LOW，不要报成 NPE/LOGIC_ERROR 等\n\n");

        // 审查清单
        sb.append("## 审查清单\n\n");
        sb.append("分类优先级：`SECRET_EXPOSURE > SECURITY > TRANSACTION > CONCURRENCY > NPE > RESOURCE_LEAK > ERROR_HANDLING > ARCHITECTURE > LOGIC_ERROR > PERFORMANCE > DEPENDENCY > HARDCODED > DEAD_CODE > CODE_STYLE > OTHER`\n");
        sb.append("举例：@DS 在接口上不生效 → LOGIC_ERROR（不是 CODE_STYLE）；硬编码密码 → SECRET_EXPOSURE（不是 HARDCODED）\n\n");

        sb.append("**P0**：SECURITY（SQL注入/${}拼接/XSS/权限绕过/反序列化/SSRF/路径穿越）、TRANSACTION（写操作缺 @Transactional）、SECRET_EXPOSURE（Java 代码中硬编码密码/Token/密钥，配置文件中的不算）\n\n");

        sb.append("**P1**：NPE（未判空就使用）、CONCURRENCY（共享变量无线程安全、HashMap 误用）、RESOURCE_LEAK（未 try-with-resources 关闭）、ERROR_HANDLING（空 catch/吞异常/finally 中 return）、ARCHITECTURE（循环依赖/Controller 直接调 DAO）、LOGIC_ERROR（条件错误/边界未处理/注解放错位置不生效）\n\n");

        sb.append("**P2**：PERFORMANCE（循环内 DB 调用/+拼接）、DEPENDENCY（SNAPSHOT/过期版本）\n\n");

        sb.append("**P3**：HARDCODED（魔法数字/写死配置）、CODE_STYLE（命名/GET 写操作/不规范但不产生 bug）、DEAD_CODE（未使用/永不执行）\n\n");

        // 严重度校准
        sb.append("## 严重度校准\n");
        sb.append("- GET 执行写操作 → CODE_STYLE + LOW，除非有实际注入/越权\n");
        sb.append("- 字段有默认值（`Boolean x = true`）→ 不要报 NPE\n");
        sb.append("- 只有实际可利用的攻击（SQL注入/XSS/SSRF等）才归 SECURITY\n\n");

        // evidence 要求
        sb.append("## evidence 要求\n");
        sb.append("- 从源码**复制原文**，禁止用文字描述代替\n");
        sb.append("- ✅ `String sql = \"DELETE FROM \" + table;`　❌「拼接了用户输入的表名」\n\n");

        // severity/category 说明
        sb.append("severity 取值：BLOCKER, HIGH, MEDIUM, LOW\n");
        sb.append("category 取值：SECURITY, NPE, TRANSACTION, CONCURRENCY, RESOURCE_LEAK, ERROR_HANDLING, SECRET_EXPOSURE, CODE_STYLE, PERFORMANCE, DEPENDENCY, ARCHITECTURE, LOGIC_ERROR, HARDCODED, DEAD_CODE, OTHER\n");
        sb.append("severity 和 category 是独立字段，不要混淆。category 不能填 \"P0 BLOCKER\"\n\n");

        // 置信度
        sb.append("## 置信度\n");
        sb.append("- 确证 → 0.85-0.99；不确定 → ≤0.70 且必须先 grep\n");
        sb.append("- 猜测性判断（含「若」「可能」「假设」）→ 不要报，先 grep\n");

        return sb.toString();
    }

    /**
     * 构建第一轮 user message（模块源码）。
     */
    private String buildIterativeFirstRoundMessage(ReviewGroup group, CodeReviewContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 项目信息\n");
        sb.append("- 项目：").append(context.getProjectName() != null ? context.getProjectName() : "unknown").append("\n");
        sb.append("- 分支：").append(context.getBranch() != null ? context.getBranch() : "unknown").append("\n\n");
        sb.append("## 模块: ").append(group.name).append("（共 ").append(group.files.size()).append(" 个文件）\n\n");

        for (FileDiff diff : group.files) {
            sb.append("### ").append(diff.getFilePath()).append("\n");
            String content = diff.getNewContent();
            if (content != null && !content.isEmpty()) {
                if (content.length() > maxSourceChars) {
                    content = content.substring(0, maxSourceChars)
                            + "\n... [truncated, total " + diff.getNewContent().length() + " chars]";
                }
                sb.append("```java\n").append(content).append("\n```\n\n");
            }
        }

        // 架构上下文
        if (group.background != null && !group.background.isEmpty()) {
            sb.append("## 架构上下文\n").append(group.background).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 构建后续轮次 user message（grep 结果）。
     */
    private String buildGrepRoundMessage(String grepResults, int remainingRounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是搜索结果（剩余 ").append(remainingRounds).append(" 轮）：\n\n");
        sb.append(grepResults).append("\n");
        sb.append("请根据以上信息：\n");
        sb.append("1. 确认或修正之前的判断，输出到 confirmed\n");
        sb.append("2. 如需更多上下文，输出 grep_requests（每轮最多 ").append(maxGrepPerRound).append(" 个）\n");
        sb.append("3. 全部确认完毕，设 done=true\n");
        return sb.toString();
    }

    /**
     * 解析 LLM 输出，失败时重试（发纠正消息）。
     */
    private IterativeReviewResult parseWithRetry(List<Map<String, Object>> messages,
                                                  String provider, String apiKey,
                                                  String apiUrl, String model,
                                                  String response) {
        // 第一次尝试解析
        IterativeReviewResult result = parseIterativeResult(response);
        if (result != null) return result;

        // 格式错误，重试最多 MAX_FORMAT_RETRIES 次
        for (int retry = 1; retry <= MAX_FORMAT_RETRIES; retry++) {
            log.warn("LLM output format error, retry {}/{}", retry, MAX_FORMAT_RETRIES);

            Map<String, Object> correctionMsg = new HashMap<>();
            correctionMsg.put("role", "user");
            correctionMsg.put("content", "你的输出解析失败，请检查以下问题并重新输出：\n" +
                    "1. category 必须是以下之一：SECURITY, NPE, TRANSACTION, CONCURRENCY, RESOURCE_LEAK, ERROR_HANDLING, SECRET_EXPOSURE, CODE_STYLE, PERFORMANCE, DEPENDENCY, ARCHITECTURE, COMPILE_ERROR, LOGIC_ERROR, HARDCODED, DEAD_CODE, OTHER\n" +
                    "   注意：不要用 \"P0 BLOCKER\" 这种严重度作为 category，severity 和 category 是两个不同字段\n" +
                    "2. severity 可选值：BLOCKER, HIGH, MEDIUM, LOW\n" +
                    "3. 只输出 JSON，不要包含其他文字");
            messages.add(correctionMsg);

            String retryResponse = llmClient.callMultiTurn(provider, apiKey, apiUrl, model,
                    messages, 8192, 0.1, "json_object");

            Map<String, Object> retryAssistantMsg = new HashMap<>();
            retryAssistantMsg.put("role", "assistant");
            retryAssistantMsg.put("content", retryResponse);
            messages.add(retryAssistantMsg);

            IterativeReviewResult retryResult = parseIterativeResult(retryResponse);
            if (retryResult != null) return retryResult;
        }

        // 重试都失败，视为结束
        log.error("LLM output format error after {} retries, ending iterative review", MAX_FORMAT_RETRIES);
        IterativeReviewResult empty = new IterativeReviewResult();
        empty.setConfirmed(Collections.emptyList());
        empty.setGrepRequests(Collections.emptyList());
        empty.setDone(true);
        return empty;
    }

    /**
     * 拆分带 --- 分隔符的 finding。
     * 如果 evidence 或 trigger 包含 ---，说明 LLM 合并了多个问题到一个 finding 中，需要拆开。
     */
    private List<Finding> splitMergedFindings(List<Finding> findings) {
        List<Finding> result = new ArrayList<>();
        for (Finding f : findings) {
            String evidence = f.getEvidence();
            String trigger = f.getTrigger();
            // 检查是否包含 --- 分隔符
            if ((evidence != null && evidence.contains("---")) || (trigger != null && trigger.contains("---"))) {
                String[] evidenceParts = evidence != null ? evidence.split("\\s*---\\s*") : new String[]{evidence};
                String[] triggerParts = trigger != null ? trigger.split("\\s*---\\s*") : new String[]{trigger};
                int count = Math.max(evidenceParts.length, triggerParts.length);
                for (int i = 0; i < count; i++) {
                    Finding split = new Finding();
                    split.setFile(f.getFile());
                    split.setStartLine(f.getStartLine());
                    split.setEndLine(f.getEndLine());
                    split.setSeverity(f.getSeverity());
                    split.setCategory(f.getCategory());
                    split.setConfidence(f.getConfidence());
                    split.setModuleName(f.getModuleName());
                    split.setEvidence(i < evidenceParts.length ? evidenceParts[i].trim() : evidence);
                    split.setTrigger(i < triggerParts.length ? triggerParts[i].trim() : trigger);
                    split.setSuggestedFix(f.getSuggestedFix());
                    result.add(split);
                }
                log.debug("Split finding with --- separator into {} parts: {}", count,
                        f.getFile() + ":" + f.getStartLine());
            } else {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * 按 file + startLine + category 去重。
     * 迭代 grep 模式下，不同模块的 LLM 可能通过 grep 发现同一位置的相同问题。
     */
    List<Finding> deduplicateByFileAndLine(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) return findings;
        Map<String, Finding> seen = new LinkedHashMap<>();
        List<Finding> result = new ArrayList<>();
        for (Finding f : findings) {
            // W5 修复 — 行号除以 3 做粗粒度去重，避免行号偏差 1-2 的重复 findings
            int lineBucket = f.getStartLine() / 3;
            String key = (f.getFile() != null ? f.getFile() : "")
                    + "#L" + lineBucket
                    + "#" + (f.getCategory() != null ? f.getCategory().name() : "");
            if (!seen.containsKey(key)) {
                seen.put(key, f);
                result.add(f);
            } else {
                log.debug("Dedup: skipping duplicate finding at {}:{} (lineBucket={})",
                        f.getFile(), f.getStartLine(), lineBucket);
            }
        }
        int removed = findings.size() - result.size();
        if (removed > 0) {
            log.info("Dedup by file+lineBucket+category: {} → {} (removed {} duplicates)",
                    findings.size(), result.size(), removed);
        }
        return result;
    }

    /**
     * 解析 JSON，返回 null 表示解析失败。
     */
    private IterativeReviewResult parseIterativeResult(String response) {
        String json = extractJson(response);
        if (json.isEmpty()) {
            log.warn("Iterative review: no valid JSON, raw={}",
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);
            return null;
        }
        try {
            IterativeReviewResult result = MAPPER.readValue(json, IterativeReviewResult.class);
            // 兜底：category 为 null 时（LLM 输出了未知枚举值），映射为 OTHER
            if (result.getConfirmed() != null) {
                for (Finding f : result.getConfirmed()) {
                    if (f.getCategory() == null) {
                        log.warn("Iterative review: unknown category for finding '{}', mapping to OTHER",
                                f.getEvidence() != null ? f.getEvidence().substring(0, Math.min(50, f.getEvidence().length())) : "?");
                        f.setCategory(FindingCategory.OTHER);
                    }
                    // severity 也可能为 null，兜底
                    if (f.getSeverity() == null) {
                        f.setSeverity(FindingSeverity.MEDIUM);
                    }
                }
            }
            if (result.getConfirmed() == null && result.getGrepRequests() == null) {
                log.warn("Iterative review: both confirmed and grep_requests are null");
                return null;
            }
            // 后处理：拆分带 --- 的 finding
            if (result.getConfirmed() != null) {
                result.setConfirmed(splitMergedFindings(result.getConfirmed()));
            }
            return result;
        } catch (Exception e) {
            log.warn("Iterative review: parse failed: {}", e.getMessage());
            return null;
        }
    }
}
