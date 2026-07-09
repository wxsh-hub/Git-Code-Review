package com.devops.ai.core.generator;

public class DocumentResult {

    private String content;
    private String format;
    private String outputPath;
    private long generationTimeMs;
    private int commitCount;
    private boolean success;
    private String errorMessage;

    private String reviewContent;
    private String reviewOutputPath;

    /** Phase 8: 效率分析内容，独立于审查报告，用于四页结构组装 */
    private String efficiencyContent;

    // 拆分报告的四个独立内容
    private String summaryContent;        // 管理摘要
    private String dispositionContent;    // 问题处置页
    private String moduleContent;         // 模块与趋势页
    private String appendixContent;       // 效率与贡献附录

    public DocumentResult() {
    }

    public DocumentResult(String content, String format) {
        this.content = content;
        this.format = format;
        this.success = true;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public long getGenerationTimeMs() {
        return generationTimeMs;
    }

    public void setGenerationTimeMs(long generationTimeMs) {
        this.generationTimeMs = generationTimeMs;
    }

    public int getCommitCount() {
        return commitCount;
    }

    public void setCommitCount(int commitCount) {
        this.commitCount = commitCount;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getReviewContent() {
        return reviewContent;
    }

    public void setReviewContent(String reviewContent) {
        this.reviewContent = reviewContent;
    }

    public String getReviewOutputPath() {
        return reviewOutputPath;
    }

    public void setReviewOutputPath(String reviewOutputPath) {
        this.reviewOutputPath = reviewOutputPath;
    }

    public String getEfficiencyContent() {
        return efficiencyContent;
    }

    public void setEfficiencyContent(String efficiencyContent) {
        this.efficiencyContent = efficiencyContent;
    }

    public String getSummaryContent() {
        return summaryContent;
    }

    public void setSummaryContent(String summaryContent) {
        this.summaryContent = summaryContent;
    }

    public String getDispositionContent() {
        return dispositionContent;
    }

    public void setDispositionContent(String dispositionContent) {
        this.dispositionContent = dispositionContent;
    }

    public String getModuleContent() {
        return moduleContent;
    }

    public void setModuleContent(String moduleContent) {
        this.moduleContent = moduleContent;
    }

    public String getAppendixContent() {
        return appendixContent;
    }

    public void setAppendixContent(String appendixContent) {
        this.appendixContent = appendixContent;
    }
}
