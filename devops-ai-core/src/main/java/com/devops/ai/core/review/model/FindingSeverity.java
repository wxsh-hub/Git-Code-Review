package com.devops.ai.core.review.model;

/**
 * 问题严重级别（P0-P4），全系统统一。
 *
 * <p>Phase 1 数据地基 — 后续 Phase 直接引用此枚举。</p>
 */
public enum FindingSeverity {

    /** P0 阻断：安全漏洞、数据丢失风险 */
    BLOCKER("P0", "阻断"),

    /** P1 高危：空指针、事务边界、并发安全 */
    HIGH("P1", "高危"),

    /** P2 中危：资源泄漏、错误处理缺失 */
    MEDIUM("P2", "中危"),

    /** P3 低危：代码风格、命名规范 */
    LOW("P3", "低危"),

    /** P4 信息：建议性改进 */
    INFO("P4", "信息");

    private final String level;
    private final String label;

    FindingSeverity(String level, String label) {
        this.level = level;
        this.label = label;
    }

    public String getLevel() { return level; }
    public String getLabel() { return label; }

    /**
     * 从 OCR 状态描述反推严重级别。
     * <p>Phase 2 会通过 LLM 结构化输出直接提供精确级别，届时此方法仅作为 fallback。</p>
     */
    public static FindingSeverity fromDescription(String text) {
        if (text == null) return INFO;
        String lower = text.toLowerCase();
        if (lower.contains("安全") || lower.contains("security") || lower.contains("注入") || lower.contains("injection")
                || lower.contains("数据丢失") || lower.contains("data loss")) {
            return BLOCKER;
        }
        if (lower.contains("空指针") || lower.contains("null") || lower.contains("npe")
                || lower.contains("事务") || lower.contains("transaction")
                || lower.contains("并发") || lower.contains("concurrent") || lower.contains("thread")) {
            return HIGH;
        }
        if (lower.contains("资源") || lower.contains("resource") || lower.contains("泄漏") || lower.contains("leak")
                || lower.contains("异常") || lower.contains("exception")) {
            return MEDIUM;
        }
        if (lower.contains("风格") || lower.contains("style") || lower.contains("命名") || lower.contains("naming")) {
            return LOW;
        }
        return INFO;
    }

    /**
     * Phase 6 — 从 review LLM 输出的 "P0"/"P1"/... 字符串映射到枚举。
     */
    public static FindingSeverity fromLevel(String level) {
        if (level == null) return INFO;
        switch (level.trim().toUpperCase()) {
            case "P0": return BLOCKER;
            case "P1": return HIGH;
            case "P2": return MEDIUM;
            case "P3": return LOW;
            case "P4": return INFO;
            default: return INFO;
        }
    }

    /**
     * 根据问题分类确定性映射到严重级别。
     *
     * <p>review LLM 对"分类"（这是什么问题）的判断比直接给 P 级更可靠，
     * 因此由代码侧根据分类查表定级，消除 LLM 偷懒给错级别的问题。</p>
     *
     * <h3>映射规则</h3>
     * <ul>
     *   <li>P0 阻断：安全漏洞、敏感信息暴露</li>
     *   <li>P1 高危：空指针、事务边界、并发安全、资源泄漏、异常处理不当</li>
     *   <li>P2 中危：性能问题、依赖风险、架构问题、逻辑错误</li>
     *   <li>P3 低危：硬编码、代码风格</li>
     *   <li>P4 信息：其他未归类问题</li>
     * </ul>
     */
    public static FindingSeverity fromCategory(FindingCategory category) {
        if (category == null) return INFO;
        switch (category) {
            // P0 阻断
            case SECURITY:
            case SECRET_EXPOSURE:
                return BLOCKER;

            // P1 高危
            case NPE:
            case TRANSACTION:
            case CONCURRENCY:
            case RESOURCE_LEAK:
            case ERROR_HANDLING:
            case ARCHITECTURE:    // 循环依赖/分层违规 — 结构性缺陷，影响面广
            case LOGIC_ERROR:     // 业务逻辑缺陷 — 直接产生错误结果
                return HIGH;

            // P2 中危
            case PERFORMANCE:
            case DEPENDENCY:
                return MEDIUM;

            // P3 低危
            case HARDCODED:
            case CODE_STYLE:
            case DEAD_CODE:       // 冗余/死代码 — 不直接导致故障但需清理
                return LOW;

            // P4 信息
            default:
                return INFO;
        }
    }
}
