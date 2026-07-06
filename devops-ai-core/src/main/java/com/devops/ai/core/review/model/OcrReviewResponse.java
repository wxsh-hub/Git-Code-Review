package com.devops.ai.core.review.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCR MCP server code_scan 返回的完整响应。
 */
public class OcrReviewResponse {
    private String status;              // success / completed_with_warnings / completed_with_errors
    private String message;
    private List<OcrComment> comments = new ArrayList<>();
    private OcrSummary summary;
    private List<OcrWarning> warnings = new ArrayList<>();
    private String projectSummary;      // OCR scan 模式的全局总结
    private Map<String, Object> toolCalls = new LinkedHashMap<>(); // tool call 统计

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<OcrComment> getComments() { return comments; }
    public void setComments(List<OcrComment> comments) { this.comments = comments; }
    public OcrSummary getSummary() { return summary; }
    public void setSummary(OcrSummary summary) { this.summary = summary; }
    public List<OcrWarning> getWarnings() { return warnings; }
    public void setWarnings(List<OcrWarning> warnings) { this.warnings = warnings; }
    public String getProjectSummary() { return projectSummary; }
    public void setProjectSummary(String projectSummary) { this.projectSummary = projectSummary; }
    public Map<String, Object> getToolCalls() { return toolCalls; }
    public void setToolCalls(Map<String, Object> toolCalls) { this.toolCalls = toolCalls; }

    /**
     * Token 消耗与耗时汇总。
     */
    public static class OcrSummary {
        private int filesReviewed;
        private int comments;
        private long totalTokens;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private String elapsed;

        public int getFilesReviewed() { return filesReviewed; }
        public void setFilesReviewed(int filesReviewed) { this.filesReviewed = filesReviewed; }
        public int getComments() { return comments; }
        public void setComments(int comments) { this.comments = comments; }
        public long getTotalTokens() { return totalTokens; }
        public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
        public long getInputTokens() { return inputTokens; }
        public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
        public long getOutputTokens() { return outputTokens; }
        public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
        public long getCacheReadTokens() { return cacheReadTokens; }
        public void setCacheReadTokens(long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
        public long getCacheWriteTokens() { return cacheWriteTokens; }
        public void setCacheWriteTokens(long cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }
        public String getElapsed() { return elapsed; }
        public void setElapsed(String elapsed) { this.elapsed = elapsed; }
    }

    /**
     * 非致命警告。
     */
    public static class OcrWarning {
        private String file;
        private String message;
        private String type;

        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }
}
