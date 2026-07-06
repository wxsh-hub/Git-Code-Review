package com.devops.ai.core.review.model;

/**
 * Finding 确认状态，代表双 LLM 交叉验证后的最终判断。
 *
 * <p>Phase 1 数据地基，Phase 6 交叉验证后赋值。</p>
 */
public enum FindingStatus {

    /** 未复核：Phase 1 初始状态，等待 Phase 6 交叉验证 */
    UNREVIEWED("未复核"),

    /** 已确认：双 LLM 置信度平均 ≥ 0.7 */
    CONFIRMED("已确认"),

    /** 误报：双 LLM 置信度平均 < 0.7 */
    FALSE_POSITIVE("误报");

    private final String label;

    FindingStatus(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }

    /**
     * 根据置信度阈值判定状态。
     *
     * @param confidence 双 LLM 平均置信度 0.0-1.0
     */
    public static FindingStatus fromConfidence(double confidence) {
        return confidence >= 0.7 ? CONFIRMED : FALSE_POSITIVE;
    }
}
