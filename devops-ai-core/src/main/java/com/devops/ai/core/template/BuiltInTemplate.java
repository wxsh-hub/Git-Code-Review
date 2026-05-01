package com.devops.ai.core.template;

public enum BuiltInTemplate {

    STANDARD("standard", "标准发布文档", "标准格式的发布文档模板"),
    COMPACT("compact", "简洁模式", "仅包含分类和提交列表"),
    DETAILED("detailed", "详细模式", "包含构建信息、分类详情、提交统计");

    private final String name;
    private final String displayName;
    private final String description;

    BuiltInTemplate(String name, String displayName, String description) {
        this.name = name;
        this.displayName = displayName;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static BuiltInTemplate fromName(String name) {
        for (BuiltInTemplate t : values()) {
            if (t.name.equals(name)) {
                return t;
            }
        }
        return STANDARD;
    }
}
