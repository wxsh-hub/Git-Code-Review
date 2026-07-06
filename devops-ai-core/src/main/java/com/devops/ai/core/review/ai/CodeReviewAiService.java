package com.devops.ai.core.review.ai;

import com.devops.ai.core.llm.LlmClient;
import com.devops.ai.core.review.model.*;
import com.devops.ai.infrastructure.entity.AiConfig;
import com.devops.ai.infrastructure.exception.AiServiceException;
import com.devops.ai.infrastructure.repository.AiConfigRepository;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class CodeReviewAiService {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewAiService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            // diff 模式：全量文件一次调用。OCR 的 code_review_diff 只审查变更行，
            // 分组没有任何收益，反而 138 个文件拆成 45 组造成 45 次 OCR 子进程调用
            List<Finding> findings = reviewAll(filtered, context);
            for (Finding f : findings) {
                if (f.getModuleName() != null) moduleSet.add(f.getModuleName());
            }
            allFindings.addAll(findings);
            log.info("Diff review complete: {} findings from {} files", findings.size(), filtered.size());
        } else {
            // scan 模式（无 hash 范围）：按业务链路分组逐组扫描
            Map<String, ReviewGroup> groups = groupByBusinessLink(filtered, context);
            for (ReviewGroup group : groups.values()) {
                try {
                    List<Finding> groupFindings = reviewGroup(group, context);
                    allFindings.addAll(groupFindings);
                    moduleSet.add(group.name);
                    log.info("Group '{}' reviewed: {} findings from {} files",
                            group.name, groupFindings.size(), group.files.size());
                } catch (Exception e) {
                    log.error("Group '{}' review failed: {}", group.name, e.getMessage());
                }
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
     * diff 模式：全量文件一次调用 OCR MCP。
     * code_review_diff 只审查变更行，分组无收益，一次传全部文件效率最高。
     */
    List<Finding> reviewAll(List<FileDiff> diffs, CodeReviewContext context) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("repo_dir", context.getRepoPath() != null ? context.getRepoPath() : "");
        args.put("background", buildGraphBackground(context));
        if (getModel() != null && !getModel().isEmpty()) {
            args.put("model", getModel());
        }

        String paths = diffs.stream()
                .map(FileDiff::getFilePath)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));
        args.put("path", paths);
        args.put("from", context.getSinceHash());
        args.put("to", context.getUntilHash());

        log.info("Reviewing all {} files via code_review_diff", diffs.size());

        String ocrJson = ocrmcpClient.callTool("code_review_diff", args);
        OcrReviewResponse ocrResult = MAPPER.readValue(ocrJson, OcrReviewResponse.class);

        List<Finding> findings = new ArrayList<>();
        List<OcrComment> comments = ocrResult.getComments();
        if (comments != null) {
            for (OcrComment cm : comments) {
                Finding f = Finding.fromOcrComment(cm);
                // moduleName diff 模式下从文件路径提取
                String module = ModulePathResolver.resolveModule(cm.getPath());
                f.setModuleName(module != null ? module : "other");
                findings.add(f);
            }
        }

        log.info("Diff review: {} ocr comments → {} findings",
                comments != null ? comments.size() : 0, findings.size());
        return findings;
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
    // 后处理管线
    // ================================================================

    /**
     * 执行审查后处理管线。
     *
     * <p>管线顺序：
     * <pre>
     *   List&lt;Finding&gt; raw
     *     │
     *     ├─ Phase 5: FindingBlameTracer.trace()          ← 对 P0/P1 做 git blame
     *     ├─ Phase 6: ReviewLlmService.crossValidate()     ← 双 LLM 交叉验证
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

        // ===== Phase 5: Blame 追溯（P0/P1）=====
        List<Finding> traced = findingBlameTracer.trace(findings, context.getRepoPath());

        // ===== Phase 6: 双 LLM 交叉验证（P0/P1）=====
        List<Finding> validated = reviewLlmService.crossValidate(traced, context);

        // ===== Phase 3: SecretDetector — 脱敏 + 新增 SECRET_EXPOSURE =====
        List<Finding> newSecretFindings = secretDetector.detectAndSanitize(validated);

        // ===== Phase 3: FindingVerifier — 行号校验/去重/误报/完整性 =====
        FilterResult fr = findingVerifier.verify(validated, context.getRepoPath());
        List<Finding> verified = fr.toPipelineOutput();

        // 追加 SecretDetector 新产出的 SECRET_EXPOSURE Finding
        verified.addAll(newSecretFindings);

        log.info("Pipeline complete: {} findings → {} after verify + {} secret → {} total",
                findings.size(), verified.size() - newSecretFindings.size(),
                newSecretFindings.size(), verified.size());
        return verified;
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
        return llmClient.call(provider, apiKey, apiUrl, model, prompt, 4096);
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
