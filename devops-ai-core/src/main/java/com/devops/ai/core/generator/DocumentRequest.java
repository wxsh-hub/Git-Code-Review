package com.devops.ai.core.generator;

import java.util.List;

public class DocumentRequest {

    private String projectId;
    private String projectName;
    private String projectVersion;
    private String branch;
    private String templateName;
    private String format;
    private String since;
    private String until;
    private String sinceHash;
    private String untilHash;
    private String author;
    private List<String> dimensions;
    private boolean incremental;
    private boolean useAiClassifier;
    private boolean useCodeReview;
    private boolean useEfficiencyAnalysis;
    private String reviewFormat;
    private List<com.devops.ai.core.model.Category> categories;
    private int totalCommits;
    private int totalAuthors;
    private String analysisReport;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getProjectVersion() {
        return projectVersion;
    }

    public void setProjectVersion(String projectVersion) {
        this.projectVersion = projectVersion;
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

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
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

    public List<com.devops.ai.core.model.Category> getCategories() {
        return categories;
    }

    public void setCategories(List<com.devops.ai.core.model.Category> categories) {
        this.categories = categories;
    }

    public int getTotalCommits() {
        return totalCommits;
    }

    public void setTotalCommits(int totalCommits) {
        this.totalCommits = totalCommits;
    }

    public int getTotalAuthors() {
        return totalAuthors;
    }

    public void setTotalAuthors(int totalAuthors) {
        this.totalAuthors = totalAuthors;
    }

    public String getAnalysisReport() {
        return analysisReport;
    }

    public void setAnalysisReport(String analysisReport) {
        this.analysisReport = analysisReport;
    }
}
