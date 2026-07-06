package com.devops.ai.core.review.model;

/**
 * 敏感信息检测命中结果。
 *
 * <p>SecretDetector 在扫描文本字段时，每命中一条规则就记录一个 SecretMatch，
 * 包含规则名、匹配位置和脱敏后的替换文本。</p>
 */
public class SecretMatch {

    /** 命中的规则名，如 "spring_datasource_password" */
    private final String ruleName;

    /** 匹配到的原文开头（截取前 40 字符，避免在日志中泄露凭据） */
    private final String snippet;

    /** 匹配位置，如 "evidence" / "trigger" / "suggestedFix" */
    private final String field;

    public SecretMatch(String ruleName, String snippet, String field) {
        this.ruleName = ruleName;
        this.snippet = snippet.length() > 40 ? snippet.substring(0, 40) + "..." : snippet;
        this.field = field;
    }

    public String getRuleName() { return ruleName; }
    public String getSnippet() { return snippet; }
    public String getField() { return field; }
}
