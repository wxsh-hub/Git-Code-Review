package com.devops.ai.core.review.report;

import com.devops.ai.core.review.model.CodeReviewResult;
import com.devops.ai.core.review.model.CodeReviewContext;
import com.devops.ai.core.review.model.Finding;
import com.devops.ai.core.review.model.FindingSeverity;
import com.devops.ai.core.review.model.FindingStatus;
import com.devops.ai.core.review.model.OcrComment;
import com.devops.ai.core.review.model.ReviewScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.*;

@Component
public class ReviewReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReviewReportGenerator.class);

    // ================================================================
    // 主入口：自动选择段落式报告或行级增强报告
    // ================================================================

    public String generate(CodeReviewResult result, CodeReviewContext context, String format) {
        // Phase 4+: 优先使用 Finding 列表渲染
        if (result.getFindings() != null && !result.getFindings().isEmpty()) {
            return generateFromFindings(result, context, format);
        }
        // 有行级评论时，使用增强格式
        if (result.hasOcrComments()) {
            return generateEnhanced(result, context, format);
        }
        // 退回到段落式格式
        if ("html".equals(format)) {
            return generateHtmlLegacy(result, context);
        }
        return generateMarkdownLegacy(result, context);
    }

    // ================================================================
    // Phase 4: Finding 驱动的报告渲染
    // ================================================================

    public String generateFromFindings(CodeReviewResult result, CodeReviewContext context, String format) {
        if ("html".equals(format)) {
            return generateFindingsHtml(result, context);
        }
        return generateFindingsMarkdown(result, context);
    }

    private String generateFindingsMarkdown(CodeReviewResult result, CodeReviewContext context) {
        List<Finding> findings = result.getFindings();
        StringBuilder sb = new StringBuilder();
        sb.append("# 代码审查报告\n\n");

        // ---- 审查范围（Phase 4 新增，放在报告第一行） ----
        if (context.getReviewScope() != null) {
            sb.append("> **审查范围**：").append(context.getScopeDescription() != null
                    ? context.getScopeDescription()
                    : context.getReviewScope().getLabel()).append("\n\n");
        }

        // 头部信息
        sb.append("| 项目 | ").append(nvl(context.getProjectName())).append(" |\n");
        sb.append("|------|---------------|\n");
        sb.append("| 版本 | ").append(nvl(context.getProjectVersion())).append(" |\n");
        sb.append("| 分支 | ").append(nvl(context.getBranch())).append(" |\n");
        sb.append("| 审查时间 | ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append(" |\n");
        if (context.getCommits() != null) {
            sb.append("| 审查范围 | ").append(context.getCommits().size()).append(" commits |\n");
        }
        sb.append("\n---\n\n");

        // 按严重度分组
        long p0 = findings.stream().filter(f -> f.getSeverity() == FindingSeverity.BLOCKER).count();
        long p1 = findings.stream().filter(f -> f.getSeverity() == FindingSeverity.HIGH).count();
        long p2 = findings.stream().filter(f -> f.getSeverity() == FindingSeverity.MEDIUM).count();

        sb.append("## 审查摘要\n\n");
        sb.append("| 级别 | 数量 |\n");
        sb.append("|------|------|\n");
        sb.append("| P0 阻断 | ").append(p0).append(" |\n");
        sb.append("| P1 高危 | ").append(p1).append(" |\n");
        sb.append("| P2 中危 | ").append(p2).append(" |\n");
        sb.append("\n");

        if (findings.isEmpty()) {
            sb.append("**审查结论**: 通过 — 未发现代码缺陷\n\n");
            return sb.toString();
        }

        // 按文件分组
        Map<String, List<Finding>> byFile = new LinkedHashMap<>();
        for (Finding f : findings) {
            String key = f.getFile() != null ? f.getFile() : "(unknown)";
            byFile.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }

        sb.append("## 问题详情\n\n");
        int idx = 1;
        for (Map.Entry<String, List<Finding>> entry : byFile.entrySet()) {
            sb.append("### ").append(idx++).append(". `").append(entry.getKey()).append("`\n\n");
            for (Finding f : entry.getValue()) {
                String sevLevel = f.getSeverity() != null ? f.getSeverity().getLevel() : "?";
                sb.append(String.format("**[%s] 第 %d-%d 行**", sevLevel, f.getStartLine(), f.getEndLine()));
                if (f.getModuleName() != null) {
                    sb.append(" [").append(f.getModuleName()).append("]");
                }
                sb.append("\n\n");

                if (f.getEvidence() != null && !f.getEvidence().isEmpty()) {
                    sb.append("> 证据：\n> ```java\n> ").append(f.getEvidence().replace("\n", "\n> ")).append("\n> ```\n\n");
                }
                if (f.getSuggestedFix() != null && !f.getSuggestedFix().isEmpty()) {
                    String fix = f.getSuggestedFix().length() > 300
                            ? f.getSuggestedFix().substring(0, 300) + "..." : f.getSuggestedFix();
                    sb.append("> 建议修复：`").append(fix).append("`\n\n");
                }

                // 归属信息（Phase 5 填充）
                if (f.getOwner() != null && !f.getOwner().isEmpty()) {
                    sb.append("> 引入者：").append(f.getOwner()).append("\n\n");
                }
            }
        }

        // 结论
        sb.append("## 审查结论\n\n");
        sb.append("**结论**: ").append(nvl(result.getConclusion())).append("\n\n");
        sb.append("**风险等级**: ").append(nvl(result.getRiskLevel())).append("\n\n");
        if (result.getKeyFindings() != null && !result.getKeyFindings().isEmpty()) {
            sb.append("**关键发现**: ").append(result.getKeyFindings()).append("\n\n");
        }

        log.info("Finding-based review report generated (markdown, {} findings, {} chars)", findings.size(), sb.length());
        return sb.toString();
    }

    private String generateFindingsHtml(CodeReviewResult result, CodeReviewContext context) {
        // HTML 版本暂用 markdown 内容包裹
        String md = generateFindingsMarkdown(result, context);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
        html.append("<title>代码审查报告 - ").append(escapeHtml(nvl(context.getProjectName()))).append("</title>");
        html.append("<style>body{font-family:-apple-system,sans-serif;max-width:900px;margin:0 auto;padding:20px;color:#333;line-height:1.6}");
        html.append("h1{color:#1a1a2e;border-bottom:2px solid #e2e8f0;padding-bottom:8px}");
        html.append("h2{color:#2d3748;margin-top:24px}");
        html.append("table{border-collapse:collapse;width:100%;margin:12px 0}");
        html.append("td,th{border:1px solid #e2e8f0;padding:8px 12px;text-align:left}");
        html.append("th{background:#f7fafc;font-weight:600}");
        html.append("code{background:#edf2f7;padding:2px 4px;border-radius:3px;font-size:0.9em}");
        html.append("pre{background:#f7fafc;padding:12px;border-radius:6px;overflow-x:auto}");
        html.append("blockquote{border-left:3px solid #e2e8f0;margin:12px 0;padding:4px 16px;color:#718096}");
        html.append("</style></head><body>");
        html.append("<pre>").append(escapeHtml(md)).append("</pre>");
        html.append("</body></html>");
        return html.toString();
    }

    // ================================================================
    // 增强版报告：行级评论 + 代码 diff 展示
    // ================================================================

    public String generateEnhanced(CodeReviewResult result, CodeReviewContext context, String format) {
        List<OcrComment> comments = result.getOcrComments();
        if (comments == null || comments.isEmpty()) {
            // 退回到段落式
            return "html".equals(format) ? generateHtmlLegacy(result, context) : generateMarkdownLegacy(result, context);
        }
        if ("html".equals(format)) {
            return generateEnhancedHtml(result, context, comments);
        }
        return generateEnhancedMarkdown(result, context, comments);
    }

    private String generateEnhancedMarkdown(CodeReviewResult result, CodeReviewContext context, List<OcrComment> comments) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 代码审查报告\n\n");

        // 头部信息
        sb.append("| 项目 | ").append(nvl(context.getProjectName())).append(" |\n");
        sb.append("|------|---------------|\n");
        sb.append("| 版本 | ").append(nvl(context.getProjectVersion())).append(" |\n");
        sb.append("| 分支 | ").append(nvl(context.getBranch())).append(" |\n");
        sb.append("| 审查时间 | ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append(" |\n");
        if (context.getCommits() != null) {
            sb.append("| 审查范围 | ").append(context.getCommits().size()).append(" commits |\n");
        }
        if (context.getFileDiffs() != null) {
            sb.append("| 变更文件 | ").append(context.getFileDiffs().size()).append(" files |\n");
        }
        sb.append("\n---\n\n");

        // 审查摘要
        sb.append("## 审查摘要\n\n");
        sb.append(result.getProjectSummary() != null
                ? result.getProjectSummary()
                : String.format("共发现 %d 个问题", comments.size()));
        sb.append("\n\n");

        // 按文件分组
        Map<String, List<OcrComment>> byFile = groupByFile(comments);
        sb.append("## 问题详情\n\n");
        int idx = 1;
        for (Map.Entry<String, List<OcrComment>> entry : byFile.entrySet()) {
            sb.append("### ").append(idx++).append(". `").append(entry.getKey()).append("`\n\n");
            for (OcrComment cm : entry.getValue()) {
                sb.append(String.format("**第 %d-%d 行**\n\n", cm.getStartLine(), cm.getEndLine()));
                sb.append(cm.getContent()).append("\n\n");

                // 代码 diff 展示
                if (cm.getExistingCode() != null && !cm.getExistingCode().isEmpty()
                        && cm.getSuggestionCode() != null && !cm.getSuggestionCode().isEmpty()) {
                    sb.append("```diff\n");
                    for (String line : cm.getExistingCode().split("\n")) {
                        sb.append("- ").append(line).append("\n");
                    }
                    for (String line : cm.getSuggestionCode().split("\n")) {
                        sb.append("+ ").append(line).append("\n");
                    }
                    sb.append("```\n\n");
                }
            }
        }

        // 审查结论
        sb.append("## 审查结论\n\n");
        sb.append("**结论**: ").append(nvl(result.getConclusion())).append("\n\n");
        sb.append("**风险等级**: ").append(nvl(result.getRiskLevel())).append("\n\n");
        if (result.getKeyFindings() != null && !result.getKeyFindings().isEmpty()) {
            sb.append("**关键发现**:\n").append(result.getKeyFindings()).append("\n\n");
        }
        if (result.getTotalTokens() > 0) {
            sb.append("**Token 消耗**: ").append(String.format("%,d", result.getTotalTokens())).append("\n\n");
        }
        if (result.getElapsed() != null && !result.getElapsed().isEmpty()) {
            sb.append("**耗时**: ").append(result.getElapsed()).append("\n\n");
        }

        log.info("Enhanced review report generated (markdown, {} chars)", sb.length());
        return sb.toString();
    }

    private String generateEnhancedHtml(CodeReviewResult result, CodeReviewContext context, List<OcrComment> comments) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
        html.append("<title>代码审查报告 - ").append(escapeHtml(nvl(context.getProjectName()))).append("</title>");
        html.append("<style>body{font-family:-apple-system,sans-serif;max-width:900px;margin:0 auto;padding:20px;color:#333;line-height:1.6}");
        html.append("h1{color:#1a1a2e;border-bottom:2px solid #e2e8f0;padding-bottom:8px}");
        html.append("h2{color:#2d3748;margin-top:24px}");
        html.append("h3{color:#4a5568;margin-top:20px;font-family:monospace}");
        html.append("table{border-collapse:collapse;width:100%;margin:12px 0}");
        html.append("td,th{border:1px solid #e2e8f0;padding:8px 12px;text-align:left}");
        html.append("th{background:#f7fafc;font-weight:600}");
        html.append("code{background:#edf2f7;padding:2px 4px;border-radius:3px;font-size:0.9em}");
        html.append("pre{background:#f7fafc;padding:12px;border-radius:6px;overflow-x:auto}");
        html.append(".comment{border-left:3px solid #e53e3e;padding:12px;margin:12px 0;background:#fff5f5}");
        html.append(".comment-header{color:#c53030;font-weight:600;margin-bottom:8px}");
        html.append(".line-tag{background:#e53e3e;color:white;padding:2px 6px;border-radius:3px;font-size:0.85em}");
        html.append(".diff-del{color:#c53030}");
        html.append(".diff-add{color:#2f855a}");
        html.append("</style></head><body>");
        html.append("<h1>代码审查报告</h1>");

        // 头部表格
        html.append("<table>");
        html.append("<tr><th>项目</th><td>").append(escapeHtml(nvl(context.getProjectName()))).append("</td></tr>");
        html.append("<tr><th>版本</th><td>").append(escapeHtml(nvl(context.getProjectVersion()))).append("</td></tr>");
        html.append("<tr><th>分支</th><td>").append(escapeHtml(nvl(context.getBranch()))).append("</td></tr>");
        html.append("<tr><th>审查时间</th><td>").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("</td></tr>");
        if (context.getCommits() != null) {
            html.append("<tr><th>审查范围</th><td>").append(context.getCommits().size()).append(" commits</td></tr>");
        }
        if (context.getFileDiffs() != null) {
            html.append("<tr><th>变更文件</th><td>").append(context.getFileDiffs().size()).append(" files</td></tr>");
        }
        html.append("</table><hr>");

        // 摘要
        html.append("<h2>审查摘要</h2>");
        html.append("<pre>").append(escapeHtml(result.getProjectSummary() != null
                ? result.getProjectSummary()
                : String.format("共发现 %d 个问题", comments.size()))).append("</pre>");

        // 按文件分组
        Map<String, List<OcrComment>> byFile = groupByFile(comments);
        html.append("<h2>问题详情</h2>");
        for (Map.Entry<String, List<OcrComment>> entry : byFile.entrySet()) {
            html.append("<h3>").append(escapeHtml(entry.getKey())).append("</h3>");
            for (OcrComment cm : entry.getValue()) {
                html.append("<div class=\"comment\">");
                html.append("<div class=\"comment-header\">");
                html.append("<span class=\"line-tag\">行 ").append(cm.getStartLine()).append("–").append(cm.getEndLine()).append("</span>");
                html.append("</div>");
                html.append("<p>").append(escapeHtml(cm.getContent())).append("</p>");

                if (cm.getExistingCode() != null && !cm.getExistingCode().isEmpty()
                        && cm.getSuggestionCode() != null && !cm.getSuggestionCode().isEmpty()) {
                    html.append("<pre>");
                    for (String line : cm.getExistingCode().split("\n")) {
                        html.append("<span class=\"diff-del\">- ").append(escapeHtml(line)).append("</span>\n");
                    }
                    for (String line : cm.getSuggestionCode().split("\n")) {
                        html.append("<span class=\"diff-add\">+ ").append(escapeHtml(line)).append("</span>\n");
                    }
                    html.append("</pre>");
                }
                html.append("</div>");
            }
        }

        // 结论
        html.append("<h2>审查结论</h2>");
        html.append("<p><strong>结论:</strong> ").append(escapeHtml(nvl(result.getConclusion()))).append("</p>");
        html.append("<p><strong>风险等级:</strong> ").append(escapeHtml(nvl(result.getRiskLevel()))).append("</p>");
        if (result.getKeyFindings() != null && !result.getKeyFindings().isEmpty()) {
            html.append("<p><strong>关键发现:</strong></p>");
            html.append("<pre>").append(escapeHtml(result.getKeyFindings())).append("</pre>");
        }
        if (result.getTotalTokens() > 0) {
            html.append("<p><strong>Token 消耗:</strong> ").append(String.format("%,d", result.getTotalTokens())).append("</p>");
        }
        if (result.getElapsed() != null && !result.getElapsed().isEmpty()) {
            html.append("<p><strong>耗时:</strong> ").append(escapeHtml(result.getElapsed())).append("</p>");
        }
        html.append("</body></html>");

        log.info("Enhanced review report generated (html, {} chars)", html.length());
        return html.toString();
    }

    // ================================================================
    // Legacy 段落式报告
    // ================================================================

    public String generateMarkdownLegacy(CodeReviewResult result, CodeReviewContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 代码审查报告\n\n");

        sb.append("| 项目 | ").append(nvl(context.getProjectName())).append(" |\n");
        sb.append("|------|---------------|\n");
        sb.append("| 版本 | ").append(nvl(context.getProjectVersion())).append(" |\n");
        sb.append("| 分支 | ").append(nvl(context.getBranch())).append(" |\n");
        sb.append("| 审查时间 | ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append(" |\n");
        if (context.getCommits() != null) {
            sb.append("| 审查范围 | ").append(context.getCommits().size()).append(" commits |\n");
        }
        if (context.getFileDiffs() != null) {
            sb.append("| 变更文件 | ").append(context.getFileDiffs().size()).append(" files |\n");
        }
        sb.append("\n---\n\n");

        appendSection(sb, "1. 代码变更说明", result.getChangeSummary());
        appendSection(sb, "2. 架构与依赖分析", result.getArchitectureAnalysis());
        appendSection(sb, "3. 潜在代码缺陷", result.getCodeIssues());
        appendSection(sb, "4. 变更影响范围", result.getImpactAnalysis());
        appendSection(sb, "5. 测试建议", result.getTestSuggestions());

        sb.append("## 6. 审查结论\n\n");
        sb.append("**结论**: ").append(nvl(result.getConclusion())).append("\n\n");
        sb.append("**风险等级**: ").append(nvl(result.getRiskLevel())).append("\n\n");
        if (result.getKeyFindings() != null && !result.getKeyFindings().isEmpty()) {
            sb.append("**关键发现**:\n").append(result.getKeyFindings()).append("\n");
        }

        log.info("Legacy review report generated (markdown, {} chars)", sb.length());
        return sb.toString();
    }

    public String generateHtmlLegacy(CodeReviewResult result, CodeReviewContext context) {
        String md = generateMarkdownLegacy(result, context);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
        html.append("<title>代码审查报告 - ").append(escapeHtml(nvl(context.getProjectName()))).append("</title>");
        html.append("<style>body{font-family:-apple-system,sans-serif;max-width:900px;margin:0 auto;padding:20px;color:#333;line-height:1.6}");
        html.append("h1{color:#1a1a2e;border-bottom:2px solid #e2e8f0;padding-bottom:8px}");
        html.append("h2{color:#2d3748;margin-top:24px}");
        html.append("table{border-collapse:collapse;width:100%;margin:12px 0}");
        html.append("td,th{border:1px solid #e2e8f0;padding:8px 12px;text-align:left}");
        html.append("th{background:#f7fafc;font-weight:600}");
        html.append("code{background:#edf2f7;padding:2px 4px;border-radius:3px;font-size:0.9em}");
        html.append("pre{background:#f7fafc;padding:12px;border-radius:6px;overflow-x:auto}");
        html.append("</style></head><body>");
        html.append("<h1>代码审查报告</h1>");
        html.append("<table>");
        html.append("<tr><th>项目</th><td>").append(escapeHtml(nvl(context.getProjectName()))).append("</td></tr>");
        html.append("<tr><th>版本</th><td>").append(escapeHtml(nvl(context.getProjectVersion()))).append("</td></tr>");
        html.append("<tr><th>分支</th><td>").append(escapeHtml(nvl(context.getBranch()))).append("</td></tr>");
        html.append("<tr><th>审查时间</th><td>").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("</td></tr>");
        if (context.getCommits() != null) {
            html.append("<tr><th>审查范围</th><td>").append(context.getCommits().size()).append(" commits</td></tr>");
        }
        if (context.getFileDiffs() != null) {
            html.append("<tr><th>变更文件</th><td>").append(context.getFileDiffs().size()).append(" files</td></tr>");
        }
        html.append("</table>");
        html.append("<hr>");

        appendHtmlSection(html, "代码变更说明", result.getChangeSummary());
        appendHtmlSection(html, "架构与依赖分析", result.getArchitectureAnalysis());
        appendHtmlSection(html, "潜在代码缺陷", result.getCodeIssues());
        appendHtmlSection(html, "变更影响范围", result.getImpactAnalysis());
        appendHtmlSection(html, "测试建议", result.getTestSuggestions());

        html.append("<h2>审查结论</h2>");
        html.append("<p><strong>结论:</strong> ").append(escapeHtml(nvl(result.getConclusion()))).append("</p>");
        html.append("<p><strong>风险等级:</strong> ").append(escapeHtml(nvl(result.getRiskLevel()))).append("</p>");
        if (result.getKeyFindings() != null && !result.getKeyFindings().isEmpty()) {
            html.append("<p><strong>关键发现:</strong></p>");
            html.append("<pre>").append(escapeHtml(result.getKeyFindings())).append("</pre>");
        }
        html.append("</body></html>");

        log.info("Legacy review report generated (html, {} chars)", html.length());
        return html.toString();
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * 按文件路径对评论分组（保留原始顺序）。
     */
    private Map<String, List<OcrComment>> groupByFile(List<OcrComment> comments) {
        Map<String, List<OcrComment>> result = new LinkedHashMap<>();
        for (OcrComment cm : comments) {
            String path = cm.getPath();
            if (path == null) path = "(unknown)";
            result.computeIfAbsent(path, k -> new ArrayList<>()).add(cm);
        }
        return result;
    }

    private void appendSection(StringBuilder sb, String title, String content) {
        sb.append("## ").append(title).append("\n\n");
        if (content != null && !content.isEmpty()) {
            sb.append(content).append("\n\n");
        } else {
            sb.append("（本次审查未发现相关内容）\n\n");
        }
    }

    private void appendHtmlSection(StringBuilder html, String title, String content) {
        html.append("<h2>").append(title).append("</h2>");
        if (content != null && !content.isEmpty()) {
            html.append("<pre>").append(escapeHtml(content)).append("</pre>");
        } else {
            html.append("<p>（本次审查未发现相关内容）</p>");
        }
    }

    private String nvl(String s) {
        return s != null ? s : "-";
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
