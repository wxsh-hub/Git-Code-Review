package com.devops.ai.core.generator;

import com.devops.ai.core.model.Category;
import com.devops.ai.core.model.Commit;
import com.devops.ai.core.model.ContributorStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MarkdownGenerator implements DocumentFormatGenerator {

    private static final Logger log = LoggerFactory.getLogger(MarkdownGenerator.class);

    @Override
    public String getFormat() {
        return "markdown";
    }

    @Override
    public DocumentResult generate(DocumentRequest request) {
        StringBuilder content = new StringBuilder();

        content.append("# ").append(request.getProjectName());
        if (request.getProjectVersion() != null && !request.getProjectVersion().isEmpty()) {
            content.append(" - ").append(request.getProjectVersion());
        }
        content.append(" 更新日志\n\n");

        content.append("## ").append(formatDateRange(request)).append("\n\n");

        content.append("### 构建信息\n");
        content.append("- 项目名称: ").append(request.getProjectName()).append("\n");
        content.append("- 构建分支: ").append(request.getBranch()).append("\n");
        content.append("- 构建时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n");
        content.append("- 提交总数: ").append(request.getTotalCommits()).append("\n");
        if (request.getTotalAuthors() > 0) {
            content.append("- 参与开发者: ").append(request.getTotalAuthors()).append(" 人\n");
        }
        content.append("\n");

        if (request.getCategories() != null) {
            for (Category category : request.getCategories()) {
                if (category.getCommits() != null && !category.getCommits().isEmpty()) {
                    content.append("### ").append(category.getName()).append("\n\n");
                    for (Commit commit : category.getCommits()) {
                        String authorInfo = commit.getAuthorName() != null ? "（作者：" + commit.getAuthorName() + "）" : "";
                        content.append("- ").append(commit.getMessage()).append(authorInfo).append("\n");
                    }
                    content.append("\n");
                }
            }
        }

        // Contributor Panoramic Analysis section
        if (request.getContributorStats() != null && !request.getContributorStats().isEmpty()) {
            content.append("\n---\n\n## 贡献者分析\n\n");

            // === Summary table ===
            content.append("### 贡献者总览\n\n");
            content.append("| 排名 | 贡献者 | 提交数 | 占比 | 主要领域 |\n");
            content.append("|------|--------|--------|------|----------|\n");

            int rank = 1;
            for (ContributorStats stats : request.getContributorStats()) {
                String mainAreas = stats.getCategoryDistribution().entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .limit(2)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.joining("、"));
                if (mainAreas.isEmpty()) mainAreas = "-";

                String flag = stats.isLowFrequency() ? " ⚠" : "";
                content.append("| ").append(rank++).append(" | ")
                        .append(stats.getAuthorName()).append(flag).append(" | ")
                        .append(stats.getCommitCount()).append(" | ")
                        .append(String.format("%.1f", stats.getPercentage())).append("% | ")
                        .append(mainAreas).append(" |\n");
            }
            content.append("\n");

            // === Per-contributor detail sections ===
            for (ContributorStats stats : request.getContributorStats()) {
                content.append("### ").append(stats.getAuthorName())
                        .append("（").append(stats.getCommitCount()).append(" 次提交，")
                        .append(String.format("%.1f", stats.getPercentage())).append("%）\n\n");

                // Category distribution
                content.append("- **提交类型分布**：");
                boolean first = true;
                for (Map.Entry<String, Integer> e : stats.getCategoryDistribution().entrySet()) {
                    if (!first) content.append("，");
                    content.append(e.getKey()).append(" ").append(e.getValue()).append(" 次");
                    first = false;
                }
                content.append("\n");

                // Commit frequency timeline
                if (stats.getCommitFrequency() != null && !stats.getCommitFrequency().isEmpty()) {
                    content.append("- **提交时间线**：");
                    first = true;
                    for (Map.Entry<String, Integer> e : stats.getCommitFrequency().entrySet()) {
                        if (!first) content.append(" / ");
                        content.append(e.getKey()).append("（").append(e.getValue()).append("）");
                        first = false;
                    }
                    content.append("\n");
                }

                // Low-frequency: list individual commits
                if (stats.isLowFrequency() && stats.getCommitDetails() != null) {
                    content.append("\n#### ⚠ 低频贡献者详细提交记录\n\n");
                    content.append("| 提交ID | 时间 | 分类 | 提交信息 |\n");
                    content.append("|--------|------|------|----------|\n");
                    for (ContributorStats.CommitDetail cd : stats.getCommitDetails()) {
                        String shortMsg = cd.getMessage() != null && cd.getMessage().length() > 60
                                ? cd.getMessage().substring(0, 60) + "..." : cd.getMessage();
                        content.append("| `").append(cd.getCommitId() != null ? cd.getCommitId() : "-").append("` | ")
                                .append(cd.getDate() != null ? cd.getDate() : "-").append(" | ")
                                .append(cd.getCategory() != null ? cd.getCategory() : "-").append(" | ")
                                .append(shortMsg != null ? shortMsg.replace("|", "\\|") : "-").append(" |\n");
                    }
                    content.append("\n");
                }

                content.append("\n");
            }
        }

        // Optional AI Contributor Analysis Report
        if (request.getContributorAnalysisReport() != null && !request.getContributorAnalysisReport().isEmpty()) {
            content.append("\n---\n\n## 贡献者智能分析报告\n\n")
                    .append(request.getContributorAnalysisReport()).append("\n");
        }

        if (request.getAnalysisReport() != null && !request.getAnalysisReport().isEmpty()) {
            content.append("\n---\n\n## 更新日志分析报告\n\n").append(request.getAnalysisReport()).append("\n");
        }

        DocumentResult result = new DocumentResult(content.toString(), "markdown");
        result.setCommitCount(request.getTotalCommits());
        return result;
    }

    private String formatDateRange(DocumentRequest request) {
        String since = request.getSince();
        String until = request.getUntil();
        if (since != null && until != null) {
            return formatDateTime(since) + " - " + formatDateTime(until);
        }
        if (since != null) {
            return formatDateTime(since) + " - 至今";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private String formatDateTime(String value) {
        if (value == null) return "";
        String[] fromFormats = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
        for (String fmt : fromFormats) {
            try {
                Date d = new SimpleDateFormat(fmt).parse(value);
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
            } catch (Exception e) {
            }
        }
        return value;
    }
}
