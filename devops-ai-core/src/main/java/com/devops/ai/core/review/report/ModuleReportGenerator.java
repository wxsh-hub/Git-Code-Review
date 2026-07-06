package com.devops.ai.core.review.report;

import com.devops.ai.core.review.ai.ModulePathResolver;
import com.devops.ai.core.review.model.Finding;
import com.devops.ai.core.review.model.FindingCategory;
import com.devops.ai.core.review.model.FindingSeverity;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 模块与趋势页生成器 — Phase 8。
 *
 * <p>第三页报告：按模块聚合风险、展示重复问题类型、趋势信号。</p>
 */
@Component
public class ModuleReportGenerator {

    /** 风险分权重：P0=10, P1=5, P2=2, P3=1, P4=0 */
    private static final Map<String, Integer> RISK_WEIGHTS = new LinkedHashMap<>();

    static {
        RISK_WEIGHTS.put("P0", 10);
        RISK_WEIGHTS.put("P1", 5);
        RISK_WEIGHTS.put("P2", 2);
        RISK_WEIGHTS.put("P3", 1);
        RISK_WEIGHTS.put("P4", 0);
    }

    /** 分类 → 展示名 */
    private static final Map<FindingCategory, String> CATEGORY_DISPLAY_NAMES = new LinkedHashMap<>();

    static {
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.NPE, "空指针风险");
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.TRANSACTION, "事务边界缺失");
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.SECRET_EXPOSURE, "敏感信息暴露");
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.SECURITY, "安全漏洞");
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.CONCURRENCY, "并发安全问题");
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.RESOURCE_LEAK, "资源泄漏");
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.ERROR_HANDLING, "错误处理缺失");
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.CODE_STYLE, "代码风格");
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.PERFORMANCE, "性能问题");
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.DEPENDENCY, "依赖风险");
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.ARCHITECTURE, "架构问题");
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.LOGIC_ERROR, "逻辑错误");
        CATEGORY_DISPLAY_NAMES.put(FindingCategory.HARDCODED, "硬编码");
    }

    // ================================================================
    // 主入口
    // ================================================================

    /**
     * 生成完整的模块与趋势页（第三页）。
     *
     * @param findings 代码审查产出的 Finding 列表
     * @return Markdown 格式的第三页内容
     */
    public String generate(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return "## 模块与趋势页\n\n*本次审查未发现代码问题，无可聚合的模块数据*\n\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 模块与趋势页\n\n");

        // §8.4.1 模块风险分布
        generateModuleRiskDistribution(sb, findings);

        // §8.4.2 重复问题类型
        generateRepeatedPatterns(sb, findings);

        // §8.4.3 趋势信号（无历史数据时跳过）
        // 首次审查不展示趋势对比
        sb.append("---\n\n");

        return sb.toString();
    }

    // ================================================================
    // §8.4.1 模块风险分布
    // ================================================================

    /**
     * 按模块聚合风险分，输出风险分布表。
     */
    void generateModuleRiskDistribution(StringBuilder sb, List<Finding> findings) {
        sb.append("### 模块风险分布\n\n");
        sb.append("| 模块 | 文件数 | P0 | P1 | P2 | P3 | P4 | 风险分 |\n");
        sb.append("|------|--------|----|----|----|----|----|--------|\n");

        // 按模块聚合
        Map<String, ModuleStats> byModule = new LinkedHashMap<>();
        for (Finding f : findings) {
            String module = f.getModuleName() != null ? f.getModuleName() : "other";
            if (module.isEmpty()) module = "other";
            ModuleStats stats = byModule.computeIfAbsent(module, k -> new ModuleStats());
            stats.fileSet.add(f.getFile());
            switch (f.getSeverity()) {
                case BLOCKER: stats.p0++; break;
                case HIGH: stats.p1++; break;
                case MEDIUM: stats.p2++; break;
                case LOW: stats.p3++; break;
                case INFO: stats.p4++; break;
            }
        }

        // 计算风险分
        for (ModuleStats stats : byModule.values()) {
            stats.riskScore = stats.p0 * 10 + stats.p1 * 5 + stats.p2 * 2 + stats.p3 * 1;
        }

        // 按风险分降序
        List<Map.Entry<String, ModuleStats>> sorted = new ArrayList<>(byModule.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().riskScore, a.getValue().riskScore));

        for (Map.Entry<String, ModuleStats> entry : sorted) {
            ModuleStats s = entry.getValue();
            sb.append("| ").append(entry.getKey()).append(" | ")
                    .append(s.fileSet.size()).append(" | ")
                    .append(s.p0 > 0 ? s.p0 : "-").append(" | ")
                    .append(s.p1 > 0 ? s.p1 : "-").append(" | ")
                    .append(s.p2 > 0 ? s.p2 : "-").append(" | ")
                    .append(s.p3 > 0 ? s.p3 : "-").append(" | ")
                    .append(s.p4 > 0 ? s.p4 : "-").append(" | ")
                    .append(s.riskScore).append(" |\n");
        }
        sb.append("\n");
    }

    // ================================================================
    // §8.4.2 重复问题类型
    // ================================================================

    /**
     * 同一 FindingCategory 出现 ≥ 2 次才列入，按出现次数降序。
     */
    void generateRepeatedPatterns(StringBuilder sb, List<Finding> findings) {
        // 按分类 + 模块聚合
        Map<FindingCategory, CategoryStats> byCategory = new LinkedHashMap<>();
        for (Finding f : findings) {
            FindingCategory cat = f.getCategory() != null ? f.getCategory() : FindingCategory.OTHER;
            String module = f.getModuleName() != null ? f.getModuleName() : "other";
            CategoryStats cs = byCategory.computeIfAbsent(cat, k -> new CategoryStats());
            cs.count++;
            cs.moduleCounts.merge(module, 1, Integer::sum);
            if (cs.typicalExample == null && f.getEvidence() != null && !f.getEvidence().isEmpty()) {
                cs.typicalExample = truncate(f.getEvidence(), 60);
            }
        }

        // 过滤：仅 ≥ 2 次
        List<Map.Entry<FindingCategory, CategoryStats>> repeated = byCategory.entrySet().stream()
                .filter(e -> e.getValue().count >= 2)
                .sorted((a, b) -> Integer.compare(b.getValue().count, a.getValue().count))
                .collect(Collectors.toList());

        if (repeated.isEmpty()) return;

        sb.append("### 重复问题类型\n\n");
        sb.append("| 问题类型 | 出现次数 | 涉及模块 | 典型问题 |\n");
        sb.append("|---------|---------|---------|---------|\n");

        for (Map.Entry<FindingCategory, CategoryStats> entry : repeated) {
            FindingCategory cat = entry.getKey();
            CategoryStats cs = entry.getValue();
            String displayName = cat != null ? CATEGORY_DISPLAY_NAMES.getOrDefault(cat, cat.getLabel()) : "其他";
            String modules = cs.moduleCounts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .map(e -> e.getKey() + "(" + e.getValue() + ")")
                    .collect(Collectors.joining(", "));
            sb.append("| ").append(displayName).append(" | ")
                    .append(cs.count).append(" | ")
                    .append(modules).append(" | ")
                    .append(cs.typicalExample != null ? cs.typicalExample : "-").append(" |\n");
        }
        sb.append("\n");
    }

    // ================================================================
    // 内部数据类
    // ================================================================

    private static class ModuleStats {
        int p0, p1, p2, p3, p4;
        int riskScore;
        Set<String> fileSet = new HashSet<>();
    }

    private static class CategoryStats {
        int count;
        Map<String, Integer> moduleCounts = new LinkedHashMap<>();
        String typicalExample;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String trimmed = text.replace("\n", " ").trim();
        if (trimmed.length() <= maxLen) return trimmed;
        return trimmed.substring(0, maxLen) + "...";
    }
}
