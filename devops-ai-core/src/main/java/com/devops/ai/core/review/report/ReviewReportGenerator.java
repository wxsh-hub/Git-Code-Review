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
import java.util.Calendar;

@Component
public class ReviewReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReviewReportGenerator.class);

    private final ModuleReportGenerator moduleReportGenerator;

    public ReviewReportGenerator(ModuleReportGenerator moduleReportGenerator) {
        this.moduleReportGenerator = moduleReportGenerator;
    }

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

        // ================================================================
        // 第一页：管理摘要
        // ================================================================
        generateManagementSummary(sb, findings, context);

        sb.append("\n---\n\n");

        // ================================================================
        // 第二页：问题处置页
        // ================================================================
        generateDispositionPage(sb, findings, context);

        sb.append("\n---\n\n");

        // ================================================================
        // 第三页：模块与趋势页
        // ================================================================
        sb.append(moduleReportGenerator.generate(findings));

        log.info("Phase 8 four-page report generated (markdown, {} findings, {} chars)", findings.size(), sb.length());
        return sb.toString();
    }

    // ================================================================
    // 第一页：管理摘要
    // ================================================================

    private void generateManagementSummary(StringBuilder sb, List<Finding> findings, CodeReviewContext context) {
        long p0 = countBySeverity(findings, FindingSeverity.BLOCKER);
        long p1 = countBySeverity(findings, FindingSeverity.HIGH);
        long p2 = countBySeverity(findings, FindingSeverity.MEDIUM);
        long p0Confirmed = countConfirmed(findings, FindingSeverity.BLOCKER);
        long p1Confirmed = countConfirmed(findings, FindingSeverity.HIGH);

        sb.append("# 管理摘要\n\n");

        // 1) 审查范围
        if (context.getReviewScope() != null) {
            sb.append("> **审查范围**：").append(context.getScopeDescription() != null
                    ? context.getScopeDescription()
                    : context.getReviewScope().getLabel()).append("\n\n");
        }

        // 2) 版本/分支
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
        if (context.getCommits() != null) {
            // 提取开发者名单
            Set<String> authors = new LinkedHashSet<>();
            for (com.devops.ai.core.model.Commit c : context.getCommits()) {
                if (c.getAuthorName() != null) authors.add(c.getAuthorName());
            }
            sb.append("| 参与开发者 | ").append(authors.size()).append(" 人 |\n");
        }
        sb.append("\n");

        // 3) 风险等级
        String riskLevel;
        String riskIcon;
        if (p0 >= 3) {
            riskLevel = "严重";
            riskIcon = "🔴";
        } else if (p0 >= 1) {
            riskLevel = "高";
            riskIcon = "🟡";
        } else if (p1 >= 5) {
            riskLevel = "高";
            riskIcon = "🟡";
        } else if (p1 >= 1) {
            riskLevel = "中";
            riskIcon = "🔵";
        } else {
            riskLevel = "低";
            riskIcon = "🟢";
        }
        sb.append("**风险等级**：").append(riskIcon).append(" ").append(riskLevel).append("\n\n");

        // 4) 问题统计表
        sb.append("### 问题统计\n\n");
        sb.append("| 级别 | 数量 | 已确认 |\n");
        sb.append("|------|------|--------|\n");
        sb.append("| P0 阻断 | ").append(p0).append(" | ").append(p0Confirmed).append(" |\n");
        sb.append("| P1 高危 | ").append(p1).append(" | ").append(p1Confirmed).append(" |\n");
        sb.append("| P2 中危 | ").append(p2).append(" | - |\n");
        sb.append("\n");

        if (findings.isEmpty()) {
            sb.append("**审查结论**: 通过 — 未发现代码缺陷\n\n");
            return;
        }

        // 5) 是否建议发布
        sb.append("### 发布建议\n\n");
        if (p0 > 0) {
            sb.append("⛔ **不建议发布** — 存在 P0 阻断性问题，须全部修复后重新审查\n\n");
        } else if (p1 >= 3) {
            sb.append("⚠ **建议修复后发布** — P1 高危问题较多，建议修复后再上线\n\n");
        } else if (p1 >= 1) {
            sb.append("⚠ **有条件发布** — 少量 P1 问题，建议排入下个迭代修复\n\n");
        } else {
            sb.append("✅ **建议发布** — 无阻断/高危问题，可正常上线\n\n");
        }

        // 具体问题见下页（第二页：问题处置页）
        sb.append("> 具体问题及修复建议请见 [问题处置页](#问题处置页)\n\n");
    }

    // ================================================================
    // 第二页：问题处置页
    // ================================================================

    private void generateDispositionPage(StringBuilder sb, List<Finding> findings, CodeReviewContext context) {
        sb.append("# 问题处置页\n\n");

        if (findings.isEmpty()) {
            sb.append("*本次审查未发现代码问题*\n\n");
            return;
        }

        // 按严重度排序：P0 → P1 → P2 → P3 → P4
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator
                .comparingInt((Finding f) -> f.getSeverity() != null ? f.getSeverity().ordinal() : 99)
                .thenComparing(f -> f.getFile() != null ? f.getFile() : "")
                .thenComparingInt(Finding::getStartLine));

        FindingSeverity currentSeverity = null;
        int sevIdx = 0;

        for (Finding f : sorted) {
            FindingSeverity s = f.getSeverity() != null ? f.getSeverity() : FindingSeverity.INFO;

            // Severity section header
            if (currentSeverity != s) {
                currentSeverity = s;
                sevIdx = 0;
                String icon;
                switch (s) {
                    case BLOCKER: icon = "⛔ P0 阻断"; break;
                    case HIGH: icon = "⚠ P1 高危"; break;
                    case MEDIUM: icon = "🔵 P2 中危"; break;
                    default: icon = s.getLevel() + " " + s.getLabel();
                }
                long count = countBySeverity(sorted, s);
                sb.append("### ").append(icon).append("（").append(count).append(" 个）\n\n");
            }
            sevIdx++;

            // P3/P4: compact multi-line display
            if (s.ordinal() >= FindingSeverity.LOW.ordinal()) {
                sb.append("- **").append(s.getLevel()).append("-").append(sevIdx).append("** `")
                        .append(f.getFile() != null ? f.getFile() : "-").append("`")
                        .append(" 第").append(f.getStartLine()).append("-").append(f.getEndLine()).append("行")
                        .append(" — ").append(f.getCategory() != null ? f.getCategory().getLabel() : "其他");
                // 问题描述（content/trigger）
                if (f.getTrigger() != null && !f.getTrigger().isEmpty()) {
                    sb.append(": ").append(f.getTrigger().replace("\n", " "));
                }
                // 证据代码
                if (f.getEvidence() != null && !f.getEvidence().isEmpty()) {
                    sb.append("\n  > ").append(f.getEvidence().replace("\n", "\n  > "));
                }
                // 建议修复
                if (f.getSuggestedFix() != null && !f.getSuggestedFix().isEmpty()) {
                    sb.append("\n  > **建议**: ").append(f.getSuggestedFix().replace("\n", " "));
                }
                sb.append("\n\n");
                continue;
            }

            // P0/P1/P2: full detail
            sb.append("---\n\n");

            // Title line
            String codeLink = buildCodeLink(context.getGitRemoteUrl(), context.getBranch(),
                    f.getFile(), f.getStartLine(), f.getEndLine());
            String categoryLabel = f.getCategory() != null ? f.getCategory().getLabel() : "其他";
            sb.append("**").append(s.getLevel()).append("-").append(sevIdx).append("** ");
            if (codeLink != null) {
                sb.append("[").append(f.getFile()).append(" 第").append(f.getStartLine())
                        .append("-").append(f.getEndLine()).append("行](").append(codeLink).append(")");
            } else {
                sb.append("`").append(f.getFile()).append("` 第").append(f.getStartLine())
                        .append("-").append(f.getEndLine()).append("行");
            }
            sb.append(" — ").append(categoryLabel).append("\n\n");

            // Metadata table
            String handler = f.getCandidateHandler() != null && !f.getCandidateHandler().isEmpty()
                    ? f.getCandidateHandler() : "待指派";
            String reviewer = f.getReviewer() != null ? f.getReviewer() + " ✓" : "-";
            String deadline = computeDeadline(f.getSeverity(), context.getReviewDate());
            String confPct = String.format("%.1f%%", f.getConfidence() * 100);
            String statusLabel = f.getStatus() != null ? f.getStatus().getLabel() : "未复核";

            sb.append("| 项目 | 内容 |\n");
            sb.append("|------|------|\n");
            sb.append("| **严重度** | ").append(s.getLevel()).append(" ").append(s.getLabel()).append(" |\n");
            sb.append("| **分类** | ").append(categoryLabel).append(" |\n");
            sb.append("| **置信度** | ").append(confPct).append(" |\n");
            sb.append("| **状态** | ").append(statusLabel).append(" |\n");
            sb.append("| **候选处理人** | ").append(handler).append(" |\n");
            sb.append("| **复核人** | ").append(reviewer).append(" |\n");
            sb.append("| **截止时间** | ").append(deadline).append(" |\n\n");

            // Evidence
            if (f.getEvidence() != null && !f.getEvidence().isEmpty()) {
                sb.append("> **证据：**\n> ```\n> ")
                        .append(f.getEvidence().replace("\n", "\n> ")).append("\n> ```\n\n");
            }

            // Trigger
            if (f.getTrigger() != null && !f.getTrigger().isEmpty()) {
                sb.append("> **触发条件：** ").append(f.getTrigger()).append("\n\n");
            }

            // Suggested fix
            if (f.getSuggestedFix() != null && !f.getSuggestedFix().isEmpty()) {
                sb.append("> **建议修复：**\n> ```diff\n> ")
                        .append(f.getSuggestedFix().replace("\n", "\n> ")).append("\n> ```\n\n");
            }
        }
    }

    // ================================================================
    // Phase 8 工具方法
    // ================================================================

    /**
     * 生成代码链接，根据 git remote URL 判断平台类型。
     */
    String buildCodeLink(String gitRemoteUrl, String branch, String filePath,
                         int startLine, int endLine) {
        if (gitRemoteUrl == null || branch == null || filePath == null) return null;

        // 标准化 URL：去掉 .git 后缀和协议前缀
        String url = gitRemoteUrl.replaceAll("\\.git$", "");
        url = url.replaceAll("^https?://", "").replaceAll("^git@", "").replace(":", "/");

        String host = url.contains("/") ? url.substring(0, url.indexOf('/')).toLowerCase() : url;
        String projectPath = url.contains("/") ? url.substring(url.indexOf('/') + 1) : "";

        String blobFormat;
        if (host.contains("github.com") || host.contains("gitee.com")) {
            blobFormat = "https://" + host + "/" + projectPath + "/blob/" + branch + "/" + filePath;
        } else {
            // GitLab（包括私有部署）使用 /-/blob/
            blobFormat = "https://" + host + "/" + projectPath + "/-/blob/" + branch + "/" + filePath;
        }

        return blobFormat + "#L" + startLine + (endLine > startLine ? "-L" + endLine : "");
    }

    /** 计算截止时间：P0="立即", P1=审查日期+3工作日, P2=审查日期+7工作日 */
    String computeDeadline(FindingSeverity severity, Date reviewDate) {
        if (severity == FindingSeverity.BLOCKER) return "立即";
        if (reviewDate == null) return "-";

        int days;
        if (severity == FindingSeverity.HIGH) days = 3;
        else if (severity == FindingSeverity.MEDIUM) days = 7;
        else return "-";

        Calendar cal = Calendar.getInstance();
        cal.setTime(reviewDate);
        int added = 0;
        while (added < days) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
            int dow = cal.get(Calendar.DAY_OF_WEEK);
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                added++;
            }
        }
        return new SimpleDateFormat("yyyy-MM-dd").format(cal.getTime());
    }

    private long countBySeverity(List<Finding> findings, FindingSeverity severity) {
        return findings.stream().filter(f -> f.getSeverity() == severity).count();
    }

    private long countConfirmed(List<Finding> findings, FindingSeverity severity) {
        return findings.stream()
                .filter(f -> f.getSeverity() == severity && f.getStatus() == FindingStatus.CONFIRMED)
                .count();
    }

    private String truncateLine(String text, int maxLen) {
        if (text == null) return "";
        String trimmed = text.replace("\n", " ").trim();
        if (trimmed.length() <= maxLen) return trimmed;
        return trimmed.substring(0, maxLen) + "...";
    }

    private String generateFindingsHtml(CodeReviewResult result, CodeReviewContext context) {
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
