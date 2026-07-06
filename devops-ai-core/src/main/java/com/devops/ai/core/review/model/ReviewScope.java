package com.devops.ai.core.review.model;

/**
 * 审查范围枚举，统一报告口径。
 *
 * <p>Phase 4 — 每份报告在开头标明审查范围，消除"78 个 commit 迭代"和
 * "初始提交/创建项目骨架"同时出现的口径混乱。</p>
 */
public enum ReviewScope {

    /** 仅检查两个 commit 之间的代码变更（1 个 commit） */
    DIFF_REVIEW("本次 diff 审查", "仅检查两个 commit 之间的代码变更"),

    /** 检查该分支上指定范围内的所有变更（> 1 commit） */
    BRANCH_REVIEW("分支范围审查", "检查该分支上指定范围内的所有变更"),

    /** 扫描整个项目的所有源文件（无指定 hash） */
    FULL_SCAN("全量项目扫描", "扫描整个项目的所有源文件");

    private final String label;
    private final String description;

    ReviewScope(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() { return label; }
    public String getDescription() { return description; }
}
