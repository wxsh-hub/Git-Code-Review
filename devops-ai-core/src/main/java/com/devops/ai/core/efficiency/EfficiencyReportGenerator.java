package com.devops.ai.core.efficiency;

import com.devops.ai.core.efficiency.model.*;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 将效率分析结果渲染为 Markdown 段落。
 * 生成的段落会被追加到代码审查报告末尾的 "## 开发者效率分析" 章节。
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
        sb.append("*分析耗时*: ").append(result.getAnalysisTimeMs()).append("ms\n\n");

        // Section 1: Developer summary table
        generateDeveloperTable(sb, result.getDeveloperEfficiencies());

        // Section 2: Repeated changes detail
        generateRepeatedChangesDetail(sb, result.getRepeatedChanges());

        // Section 3: Churn hotspots
        generateChurnHotspots(sb, result.getRepeatedChanges());

        // Section 4: Key observations
        generateKeyObservations(sb, result);

        return sb.toString();
    }

    /**
     * 开发者效率总表。
     */
    private void generateDeveloperTable(StringBuilder sb, List<DeveloperEfficiency> devs) {
        sb.append("### 开发者效率总表\n\n");
        if (devs == null || devs.isEmpty()) {
            sb.append("*无数据*\n\n");
            return;
        }

        sb.append("| 排名 | 开发者 | 提交数 | 重复修改数 | 引入问题 | 修复他人 | 功能增强 | 效率评分 |\n");
        sb.append("|------|--------|--------|------------|----------|----------|----------|----------|\n");
        int rank = 1;
        for (DeveloperEfficiency dev : devs) {
            sb.append("| ").append(rank++).append(" | ")
                    .append(dev.getAuthorName()).append(" | ")
                    .append(dev.getTotalCommits()).append(" | ")
                    .append(dev.getRepeatedChangeCount()).append(" | ")
                    .append(dev.getFixesIntroduced()).append(" | ")
                    .append(dev.getFixesMadeForOthers()).append(" | ")
                    .append(dev.getEnhancementsMade()).append(" | ")
                    .append(String.format("%.1f", dev.getEfficiencyScore())).append(" |\n");
        }
        sb.append("\n");

        // Efficiency score legend
        sb.append("*评分说明*: 提交数基础分 + 修复他人加分(+1.5) + 功能增强加分(+1.0) - 引入问题扣分(-2.0)\n\n");
    }

    /**
     * 重复修改详情（按文件分组）。
     */
    private void generateRepeatedChangesDetail(StringBuilder sb, List<RepeatedChange> changes) {
        sb.append("### 重复修改详情\n\n");

        if (changes == null || changes.isEmpty()) {
            sb.append("*未检测到重复修改*\n\n");
            return;
        }

        // Count by intent
        long fixCount = changes.stream().filter(RepeatedChange::isFix).count();
        long enhanceCount = changes.stream().filter(RepeatedChange::isEnhance).count();
        long deleteCount = changes.stream().filter(rc -> rc.getLastChange() != null && rc.getLastChange().isDelete()).count();
        long uncertainCount = changes.size() - fixCount - enhanceCount;
        sb.append(String.format("*共 %d 处重复修改*: %d 修复 / %d 增强 / %d 删除 / %d 不确定\n\n",
                changes.size(), fixCount, enhanceCount, deleteCount, uncertainCount));

        // Group by file
        Map<String, List<RepeatedChange>> byFile = changes.stream()
                .collect(Collectors.groupingBy(RepeatedChange::getFilePath,
                        LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<RepeatedChange>> entry : byFile.entrySet()) {
            String filePath = entry.getKey();
            sb.append("#### ").append(filePath).append(" (").append(entry.getValue().size()).append(" 处)\n\n");

            int idx = 1;
            for (RepeatedChange rc : entry.getValue()) {
                sb.append("**#").append(idx++).append("** ");
                sb.append(getIntentEmoji(rc.getIntent())).append(" ")
                        .append(rc.getIntent()).append(" (").append(rc.getConfidence()).append(")");

                ChangeRecord first = rc.getFirstChange();
                ChangeRecord last = rc.getLastChange();
                if (first != null && last != null) {
                    sb.append("\n- 首次: **").append(first.getAuthorName()).append("**")
                            .append(" (").append(first.getCommitId().substring(0, Math.min(8, first.getCommitId().length()))).append(")")
                            .append(" - ").append(formatDate(first.getTimestamp()));
                    if (first.getCommitMessage() != null) {
                        sb.append(" - *").append(truncate(first.getCommitMessage(), 80)).append("*");
                    }
                    String lastAction = last.isDelete() ? " **删除**" : " 修改";
                    sb.append("\n- 再次").append(lastAction).append(": **").append(last.getAuthorName()).append("**")
                            .append(" (").append(last.getCommitId().substring(0, Math.min(8, last.getCommitId().length()))).append(")")
                            .append(" - ").append(formatDate(last.getTimestamp()));
                    if (last.getCommitMessage() != null) {
                        sb.append(" - *").append(truncate(last.getCommitMessage(), 80)).append("*");
                    }
                }
                if (rc.getAiReasoning() != null && !rc.getAiReasoning().isEmpty()) {
                    sb.append("\n- AI 判断: ").append(rc.getAiReasoning());
                }
                sb.append("\n\n");
            }
        }
    }

    /**
     * 代码改动热点（重复修改最多的文件）。
     */
    private void generateChurnHotspots(StringBuilder sb, List<RepeatedChange> changes) {
        sb.append("### 代码改动热点 (Top 5)\n\n");

        if (changes == null || changes.isEmpty()) {
            sb.append("*无数据*\n\n");
            return;
        }

        Map<String, Long> fileChurn = changes.stream()
                .collect(Collectors.groupingBy(RepeatedChange::getFilePath, Collectors.counting()));

        List<Map.Entry<String, Long>> sorted = fileChurn.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .collect(Collectors.toList());

        sb.append("| 文件 | 重复修改次数 |\n");
        sb.append("|------|-------------|\n");
        for (Map.Entry<String, Long> e : sorted) {
            String heatIndicator = e.getValue() >= 5 ? " 🔥" : e.getValue() >= 3 ? " ⚠️" : "";
            sb.append("| ").append(e.getKey()).append(heatIndicator)
                    .append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n");
        sb.append("*🔥 高热度 (≥5次)  ⚠️ 中热度 (≥3次)*\n\n");
    }

    /**
     * 关键观察和建议。
     */
    private void generateKeyObservations(StringBuilder sb, EfficiencyAnalysisResult result) {
        sb.append("### 关键观察\n\n");

        List<DeveloperEfficiency> devs = result.getDeveloperEfficiencies();
        List<RepeatedChange> changes = result.getRepeatedChanges();

        if (devs == null || devs.isEmpty()) {
            sb.append("*暂无足够数据生成观察*\n");
            return;
        }

        List<String> observations = new ArrayList<>();

        // Highest efficiency
        if (!devs.isEmpty()) {
            DeveloperEfficiency best = devs.get(0);
            observations.add("效率评分最高: **" + best.getAuthorName() + "** (" +
                    String.format("%.1f", best.getEfficiencyScore()) + " 分)");
        }

        // Most fixes made
        DeveloperEfficiency mostFixes = devs.stream()
                .max(Comparator.comparingInt(DeveloperEfficiency::getFixesMadeForOthers))
                .orElse(null);
        if (mostFixes != null && mostFixes.getFixesMadeForOthers() > 0) {
            observations.add("修复他人代码最多: **" + mostFixes.getAuthorName() + "** (" +
                    mostFixes.getFixesMadeForOthers() + " 次)");
        }

        // Most bugs introduced
        DeveloperEfficiency mostBugs = devs.stream()
                .max(Comparator.comparingInt(DeveloperEfficiency::getFixesIntroduced))
                .orElse(null);
        if (mostBugs != null && mostBugs.getFixesIntroduced() > 0) {
            observations.add("代码被修复最多: **" + mostBugs.getAuthorName() + "** (" +
                    mostBugs.getFixesIntroduced() + " 次，建议加强代码审查)");
        }

        // Overall churn rate
        if (changes != null && !changes.isEmpty()) {
            long fixCount = changes.stream().filter(RepeatedChange::isFix).count();
            double fixRate = (double) fixCount / changes.size();
            if (fixRate > 0.5) {
                observations.add(String.format("重复修改中 %.0f%% 为 Bug 修复，代码质量可能需要团队共同关注",
                        fixRate * 100));
            }
        }

        for (String obs : observations) {
            sb.append("- ").append(obs).append("\n");
        }
        sb.append("\n");
    }

    private String getIntentEmoji(RepeatedChange.Intent intent) {
        switch (intent) {
            case FIX: return "🐛";
            case ENHANCE: return "✨";
            case UNCERTAIN:
            default: return "❓";
        }
    }

    private String formatDate(Date date) {
        if (date == null) return "unknown";
        synchronized (DATE_FORMAT) {
            return DATE_FORMAT.format(date);
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
