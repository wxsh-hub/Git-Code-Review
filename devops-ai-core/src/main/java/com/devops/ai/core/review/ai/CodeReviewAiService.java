package com.devops.ai.core.review.ai;

import com.devops.ai.core.llm.LlmClient;
import com.devops.ai.core.review.model.*;
import com.devops.ai.infrastructure.entity.AiConfig;
import com.devops.ai.infrastructure.exception.AiServiceException;
import com.devops.ai.infrastructure.repository.AiConfigRepository;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CodeReviewAiService {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewAiService.class);

    private static final Map<String, String> DEFAULT_API_URLS = new LinkedHashMap<>();

    static {
        DEFAULT_API_URLS.put("deepseek", "https://api.deepseek.com");
        DEFAULT_API_URLS.put("openai", "https://api.openai.com");
        DEFAULT_API_URLS.put("anthropic", "https://api.anthropic.com");
    }

    private final LlmClient llmClient;
    private final AiConfigRepository aiConfigRepository;
    private final ConfigEncryptor configEncryptor;

    public CodeReviewAiService(LlmClient llmClient, AiConfigRepository aiConfigRepository,
                               ConfigEncryptor configEncryptor) {
        this.llmClient = llmClient;
        this.aiConfigRepository = aiConfigRepository;
        this.configEncryptor = configEncryptor;
    }

    public CodeReviewResult review(CodeReviewContext context) {
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

    private String buildReviewPrompt(CodeReviewContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个资深的 Java 代码审查专家。请审查以下代码变更。\n\n");
        sb.append("## 项目信息\n");
        sb.append("- 项目：").append(context.getProjectName() != null ? context.getProjectName() : "unknown").append("\n");
        sb.append("- 版本：").append(context.getProjectVersion() != null ? context.getProjectVersion() : "unknown").append("\n");
        sb.append("- 分支：").append(context.getBranch() != null ? context.getBranch() : "unknown").append("\n\n");

        // File diffs
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

        // Graph analysis (from code-review-graph CLI)
        String graphJson = context.getGraphAnalysisJson();
        if (graphJson != null && !graphJson.isEmpty()) {
            sb.append("## code-review-graph 静态分析结果\n");
            sb.append("```json\n").append(graphJson).append("\n```\n\n");
        } else {
            // Fallback: use in-memory graph summary
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

                // Impact scope
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

        // Try to extract sections by markdown headers
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

        // Fill missing from raw response
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
