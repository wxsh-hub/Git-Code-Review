package com.devops.ai.api.dto;

import javax.validation.constraints.NotBlank;
import java.util.List;

public class GenerateRequest {

    @NotBlank(message = "项目ID不能为空")
    private String projectId;

    private String branch;

    private String templateName;

    private String format = "markdown";

    private String since;

    private String until;

    private String author;

    private String sinceHash;

    private String untilHash;

    private List<String> dimensions;

    private boolean incremental;

    private boolean useAiClassifier;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
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
}
