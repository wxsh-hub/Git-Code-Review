package com.devops.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public class GenerateRequest {

    @Schema(description = "项目编码（必填，通过/api/v1/projects接口获取）", required = true, example = "user-service")
    private String projectCode;

    @Schema(description = "Git分支名称，不指定则使用项目默认分支", example = "main")
    private String branch;

    @Schema(description = "模板名称，不指定则使用项目默认模板", example = "default")
    private String templateName;

    @Schema(description = "输出格式: markdown / html", example = "markdown", defaultValue = "markdown")
    private String format = "markdown";

    @Schema(description = "起始时间，格式: yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss", example = "2026-04-01")
    private String since;

    @Schema(description = "截止时间，格式同上，不指定则默认为当前时间", example = "2026-05-01")
    private String until;

    @Schema(description = "筛选指定作者的提交", example = "张三")
    private String author;

    @Schema(description = "起始Commit Hash（与since/until时间范围二选一）", example = "abc123")
    private String sinceHash;

    @Schema(description = "截止Commit Hash（与since/until时间范围二选一）", example = "def456")
    private String untilHash;

    @Schema(description = "项目版本号", example = "v1.0.0")
    private String projectVersion;

    @Schema(description = "生成维度: author / time / project", example = "[\"author\", \"time\"]")
    private List<String> dimensions;

    @Schema(description = "是否增量生成（仅处理新Commit）", example = "false", defaultValue = "false")
    private boolean incremental;

    @Schema(description = "是否使用AI智能分类", example = "true", defaultValue = "false")
    private boolean useAiClassifier;

    @Schema(description = "是否生成代码审查文档", example = "false", defaultValue = "false")
    private boolean useCodeReview;

    @Schema(description = "是否启用开发者效率分析（分析重复修改、fix vs enhance 分类）", example = "false", defaultValue = "false")
    private boolean useEfficiencyAnalysis;

    @Schema(description = "审查文档输出格式: markdown / html", example = "markdown", defaultValue = "markdown")
    private String reviewFormat = "markdown";

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getSince() {
        return since;
    }

    public void setSince(String since) {
        this.since = since;
    }

    public String getUntil() {
        return until;
    }

    public void setUntil(String until) {
        this.until = until;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getSinceHash() {
        return sinceHash;
    }

    public void setSinceHash(String sinceHash) {
        this.sinceHash = sinceHash;
    }

    public String getUntilHash() {
        return untilHash;
    }

    public void setUntilHash(String untilHash) {
        this.untilHash = untilHash;
    }

    public String getProjectVersion() {
        return projectVersion;
    }

    public void setProjectVersion(String projectVersion) {
        this.projectVersion = projectVersion;
    }

    public List<String> getDimensions() {
        return dimensions;
    }

    public void setDimensions(List<String> dimensions) {
        this.dimensions = dimensions;
    }

    public boolean isIncremental() {
        return incremental;
    }

    public void setIncremental(boolean incremental) {
        this.incremental = incremental;
    }

    public boolean isUseAiClassifier() {
        return useAiClassifier;
    }

    public void setUseAiClassifier(boolean useAiClassifier) {
        this.useAiClassifier = useAiClassifier;
    }

    public boolean isUseCodeReview() {
        return useCodeReview;
    }

    public void setUseCodeReview(boolean useCodeReview) {
        this.useCodeReview = useCodeReview;
    }

    public boolean isUseEfficiencyAnalysis() {
        return useEfficiencyAnalysis;
    }

    public void setUseEfficiencyAnalysis(boolean useEfficiencyAnalysis) {
        this.useEfficiencyAnalysis = useEfficiencyAnalysis;
    }

    public String getReviewFormat() {
        return reviewFormat;
    }

    public void setReviewFormat(String reviewFormat) {
        this.reviewFormat = reviewFormat;
    }
}
