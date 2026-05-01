package com.devops.ai.core.model;

public enum Dimension {

    AUTHOR("author", "开发者维度"),
    PROJECT("project", "项目维度"),
    BRANCH("branch", "分支维度"),
    COMMIT_HASH("commitHash", "提交Hash维度"),
    TIME("time", "时间维度");

    private final String code;
    private final String description;

    Dimension(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static Dimension fromCode(String code) {
        for (Dimension d : values()) {
            if (d.code.equals(code)) {
                return d;
            }
        }
        throw new IllegalArgumentException("Unknown dimension code: " + code);
    }
}
