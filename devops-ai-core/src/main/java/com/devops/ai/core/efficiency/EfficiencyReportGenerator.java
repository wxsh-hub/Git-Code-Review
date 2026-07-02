package com.devops.ai.core.efficiency;

import com.devops.ai.core.efficiency.model.*;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 将效率分析结果渲染为 Markdown 段落（v2）。
 * 基于 AI 提交分类 + git blame 反向溯源，展示每人引入的 bug 和修复记录。
 */
@Component
public class EfficiencyReportGenerator {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    /**
     * 生成 Markdown 格式的效率分析报告段落。
     */
    public String generate(EfficiencyAnalysisResult result) {
        if (result == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 开发者效率分析\n\n");

        // Analysis metadata
        sb.append("*分析范围*: ").append(result.getProjectName() != null ? result.getProjectName() : "unknown");
        if (result.getBranch() != null) {
            sb.append(", 分支: ").append(result.getBranch());
        }
        sb.append("\n");
        sb.append("*分析模式*: Bug修复溯源（AI 提交分类 + git blame）\n");
        sb.append("*分析耗时*: ").append(result.getAnalysisTimeMs()).append("ms\n\n");

        List<DeveloperEfficiency> devs = result.getDeveloperEfficiencies();
        if (devs == null || devs.isEmpty()) {
            sb.append("*未检测到 Bug 修复提交，无法生成效率分析*\n\n");
            return sb.toString();
        }

        // Section 1: Per-developer bug details
        generateDeveloperBugDetails(sb, devs);

        // Section 2: Summary tables
        generateSummaryTables(sb, devs);

        // Section 3: Key observations
        generateKeyObservations(sb, result, devs);

        return sb.toString();
    }

    /**
     * 每个开发者的 Bug 详情。
     */
    private void generateDeveloperBugDetails(StringBuilder sb, List<DeveloperEfficiency> devs) {
        sb.append("### 开发者 Bug 详情\n\n");

        for (DeveloperEfficiency dev : devs) {
            List<DeveloperEfficiency.BugDetail> bugDetails = dev.getBugDetails();
            List<DeveloperEfficiency.FixDetail> fixDetails = dev.getFixDetails();

            if (bugDetails.isEmpty() && fixDetails.isEmpty()) {
                sb.append("#### ").append(dev.getAuthorName())
                        .append("（提交 ").append(dev.getTotalCommits()).append(" 次，未引入/修复 bug）\n\n");
                continue;
            }

            sb.append("#### ").append(dev.getAuthorName())
                    .append("（提交 ").append(dev.getTotalCommits()).append(" 次");

            if (dev.getBugsIntroduced() > 0) {
                sb.append(", 产生 ").append(String.format("%.2f", dev.getBugsIntroduced())).append(" 个 bug");
                sb.append(", bug率 ").append(String.format("%.1f%%", dev.getBugRate() * 100));
            }
            sb.append("）\n\n");

            // Bug introduction table
            if (!bugDetails.isEmpty()) {
                sb.append("##### 引入的 Bug\n\n");
                sb.append("| # | 问题提交 | 时间 | 引入描述 | 修复描述 | 文件 | 份额 | 修复人 | 修复提交 |\n");
                sb.append("|---|---------|------|---------|---------|------|------|--------|--------|\n");
                int idx = 1;
                for (DeveloperEfficiency.BugDetail bug : bugDetails) {
                    String commitShort = bug.getCommitId() != null && bug.getCommitId().length() >= 8
                            ? bug.getCommitId().substring(0, 8) : (bug.getCommitId() != null ? bug.getCommitId() : "-");
                    String fixCommitShort = formatMultiCommitIds(bug.getFixedCommitId());
                    sb.append("| ").append(idx++).append(" | ")
                            .append(commitShort).append(" | ")
                            .append(bug.getCreatedAt() != null && !bug.getCreatedAt().isEmpty() ? bug.getCreatedAt() : "-").append(" | ")
                            .append(truncate(bug.getCommitMessage(), 40)).append(" | ")
                            .append(truncate(bug.getFixedMessage(), 50)).append(" | ")
                            .append(truncateFilePath(bug.getFilePath(), 40)).append(" | ")
                            .append(String.format("%.2f", bug.getShare())).append(" | ")
                            .append(bug.getFixedBy() != null ? bug.getFixedBy() : "-").append(" | ")
                            .append(fixCommitShort).append(" |\n");
                }
                sb.append("\n");
            }

            // Fix table
            if (!fixDetails.isEmpty()) {
                sb.append("##### 修复的 Bug\n\n");
                sb.append("| # | 修复提交 | 时间 | 描述 | 被修复人 | 问题提交 |\n");
                sb.append("|---|---------|------|------|---------|--------|\n");
                int idx = 1;
                for (DeveloperEfficiency.FixDetail fix : fixDetails) {
                    String commitShort = fix.getCommitId() != null && fix.getCommitId().length() >= 8
                            ? fix.getCommitId().substring(0, 8) : (fix.getCommitId() != null ? fix.getCommitId() : "-");
                    String introCommitShort = formatMultiCommitIds(fix.getIntroducedByCommitId());
                    sb.append("| ").append(idx++).append(" | ")
                            .append(commitShort).append(" | ")
                            .append(fix.getCreatedAt() != null && !fix.getCreatedAt().isEmpty() ? fix.getCreatedAt() : "-").append(" | ")
                            .append(truncate(fix.getCommitMessage(), 50)).append(" | ")
                            .append(fix.getIntroducedBy() != null ? fix.getIntroducedBy() : "-").append(" | ")
                            .append(introCommitShort).append(" |\n");
                }
                sb.append("\n");
            }
        }
    }

    /**
     * 汇总表格：Bug 引入排名 + Bug 修复排名。
     */
    private void generateSummaryTables(StringBuilder sb, List<DeveloperEfficiency> devs) {
        sb.append("---\n\n");
        sb.append("### 汇总表\n\n");

        // Bug introduction ranking (sorted by bugsIntroduced descending)
        sb.append("#### Bug 引入排名\n\n");
        sb.append("| 排名 | 开发者 | 总提交 | 产生 Bug 数 | Bug 率 | 被修复次数 |\n");
        sb.append("|------|--------|--------|------------|--------|----------|\n");
        int rank = 1;
        for (DeveloperEfficiency dev : devs) {
            if (dev.getBugsIntroduced() == 0 && dev.getBugDetails().isEmpty()) continue;
            sb.append("| ").append(rank++).append(" | ")
                    .append(dev.getAuthorName()).append(" | ")
                    .append(dev.getTotalCommits()).append(" | ")
                    .append(String.format("%.2f", dev.getBugsIntroduced())).append(" | ")
                    .append(String.format("%.1f%%", dev.getBugRate() * 100)).append(" | ")
                    .append(dev.getBugDetails().size()).append(" |\n");
        }
        if (rank == 1) {
            sb.append("| - | *所有开发者均未引入 bug* | - | - | - | - |\n");
        }
        sb.append("\n");

        // Bug fix ranking (sorted by fixesMade descending)
        List<DeveloperEfficiency> fixersSorted = new ArrayList<>(devs);
        fixersSorted.sort((a, b) -> Integer.compare(b.getFixesMade(), a.getFixesMade()));

        sb.append("#### Bug 修复排名\n\n");
        sb.append("| 排名 | 开发者 | 修复 Bug 数 | 修复对象 |\n");
        sb.append("|------|--------|------------|--------|\n");
        rank = 1;
        for (DeveloperEfficiency dev : fixersSorted) {
            if (dev.getFixesMade() == 0) continue;
            // Build fix target summary
            Map<String, Integer> fixTargets = new LinkedHashMap<>();
            for (DeveloperEfficiency.FixDetail fix : dev.getFixDetails()) {
                String target = fix.getIntroducedBy() != null ? fix.getIntroducedBy() : "未知";
                fixTargets.merge(target, 1, Integer::sum);
            }
            StringBuilder targets = new StringBuilder();
            for (Map.Entry<String, Integer> e : fixTargets.entrySet()) {
                if (targets.length() > 0) targets.append(", ");
                targets.append(e.getKey()).append("(").append(e.getValue()).append(")");
            }
            sb.append("| ").append(rank++).append(" | ")
                    .append(dev.getAuthorName()).append(" | ")
                    .append(dev.getFixesMade()).append(" | ")
                    .append(targets.toString()).append(" |\n");
        }
        if (rank == 1) {
            sb.append("| - | *无人修复他人 bug* | - | - |\n");
        }
        sb.append("\n");
    }

    /**
     * 关键观察。
     */
    private void generateKeyObservations(StringBuilder sb, EfficiencyAnalysisResult result,
                                          List<DeveloperEfficiency> devs) {
        sb.append("### 关键观察\n\n");

        List<String> observations = new ArrayList<>();

        // Highest bug rate
        DeveloperEfficiency highestBugRate = devs.stream()
                .filter(d -> d.getBugRate() > 0)
                .max(Comparator.comparingDouble(DeveloperEfficiency::getBugRate))
                .orElse(null);
        if (highestBugRate != null) {
            observations.add("Bug 率最高: **" + highestBugRate.getAuthorName() + "** (" +
                    String.format("%.1f%%", highestBugRate.getBugRate() * 100) + ")");
        }

        // Most fixes
        DeveloperEfficiency mostFixes = devs.stream()
                .max(Comparator.comparingInt(DeveloperEfficiency::getFixesMade))
                .orElse(null);
        if (mostFixes != null && mostFixes.getFixesMade() > 0) {
            observations.add("修复最多: **" + mostFixes.getAuthorName() + "** (" +
                    mostFixes.getFixesMade() + " 次)");
        }

        // Most bugs introduced
        DeveloperEfficiency mostBugs = devs.stream()
                .max(Comparator.comparingDouble(DeveloperEfficiency::getBugsIntroduced))
                .orElse(null);
        if (mostBugs != null && mostBugs.getBugsIntroduced() > 0) {
            observations.add("产生 bug 最多: **" + mostBugs.getAuthorName() + "** (" +
                    String.format("%.2f", mostBugs.getBugsIntroduced()) + " 个，建议加强代码审查)");
        }

        // Summary
        observations.add("共 " + (result.getTotalFixCommits() > 0 ? result.getTotalFixCommits() + " 个" : "无")
                + " fix commit，追溯到 " + result.getAllBugDetails().size() + " 个 bug 归属记录");

        // Average bug rate
        double avg = devs.stream()
                .filter(d -> d.getBugRate() > 0)
                .mapToDouble(DeveloperEfficiency::getBugRate)
                .average().orElse(0);
        if (avg > 0) {
            observations.add("平均 bug 率: " + String.format("%.1f%%", avg * 100));
        }

        for (String obs : observations) {
            sb.append("- ").append(obs).append("\n");
        }
        sb.append("\n");
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String trimmed = text.replace("\n", " ").trim();
        if (trimmed.length() <= maxLen) return trimmed;
        return trimmed.substring(0, maxLen) + "...";
    }

    private String truncateFilePath(String path, int maxLen) {
        if (path == null) return "";
        if (path.length() <= maxLen) return path;
        // Show the last part of the file path
        return "..." + path.substring(path.length() - maxLen + 3);
    }

    /**
     * Format possibly multi-valued commit IDs (separated by "; ") to short 8-char hashes.
     */
    private String formatMultiCommitIds(String commitIds) {
        if (commitIds == null || commitIds.isEmpty()) return "-";
        String[] parts = commitIds.split("; ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("; ");
            String cid = parts[i].trim();
            if (cid.length() >= 8) {
                sb.append(cid, 0, 8);
            } else {
                sb.append(cid);
            }
        }
        return sb.toString();
    }
}
