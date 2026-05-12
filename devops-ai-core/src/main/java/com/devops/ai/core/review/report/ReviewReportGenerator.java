package com.devops.ai.core.review.report;

import com.devops.ai.core.review.model.CodeReviewResult;
import com.devops.ai.core.review.model.CodeReviewContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;

@Component
public class ReviewReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReviewReportGenerator.class);

    public String generate(CodeReviewResult result, CodeReviewContext context, String format) {
        if ("html".equals(format)) {
            return generateHtml(result, context);
        }
        return generateMarkdown(result, context);
    }

    public String generateMarkdown(CodeReviewResult result, CodeReviewContext context) {
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

        log.info("Review report generated (markdown, {} chars)", sb.length());
        return sb.toString();
    }

    public String generateHtml(CodeReviewResult result, CodeReviewContext context) {
        String md = generateMarkdown(result, context);
        // Simple HTML wrapper
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

        log.info("Review report generated (html, {} chars)", html.length());
        return html.toString();
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
