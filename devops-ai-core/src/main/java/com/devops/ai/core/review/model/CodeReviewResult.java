package com.devops.ai.core.review.model;

import java.util.ArrayList;
import java.util.List;

public class CodeReviewResult {
    // --- 旧字段（段落式报告）---
    private String changeSummary;
    private String architectureAnalysis;
    private String codeIssues;
    private String impactAnalysis;
    private String testSuggestions;
    private String conclusion;
    private String riskLevel;
    private String keyFindings;
    private String rawResponse;

    // --- 新增字段（OCR 行级审查）---
    private List<OcrComment> ocrComments = new ArrayList<>();  // OCR 行级评论
    private String ocrStatus;                                   // success / completed_with_warnings / ...
    private long totalTokens;                                   // Token 消耗
    private String elapsed;                                     // 审查耗时
    private String projectSummary;                              // 全局摘要（scan 模式）
    private OcrReviewResponse.OcrSummary ocrSummary;            // 详细 token/耗时 统计

    // --- Phase 4: 审查流水线产出的统一 Finding 列表 ---
    private List<Finding> findings = new ArrayList<>();         // OCR→Finding 转换后的统一问题列表

    // --- 旧字段 getter/setter ---
    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; }
    public String getArchitectureAnalysis() { return architectureAnalysis; }
    public void setArchitectureAnalysis(String architectureAnalysis) { this.architectureAnalysis = architectureAnalysis; }
    public String getCodeIssues() { return codeIssues; }
    public void setCodeIssues(String codeIssues) { this.codeIssues = codeIssues; }
    public String getImpactAnalysis() { return impactAnalysis; }
    public void setImpactAnalysis(String impactAnalysis) { this.impactAnalysis = impactAnalysis; }
    public String getTestSuggestions() { return testSuggestions; }
    public void setTestSuggestions(String testSuggestions) { this.testSuggestions = testSuggestions; }
    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public String getKeyFindings() { return keyFindings; }
    public void setKeyFindings(String keyFindings) { this.keyFindings = keyFindings; }
    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

    // --- 新增字段 getter/setter ---
    public List<OcrComment> getOcrComments() { return ocrComments; }
    public void setOcrComments(List<OcrComment> ocrComments) { this.ocrComments = ocrComments; }
    public String getOcrStatus() { return ocrStatus; }
    public void setOcrStatus(String ocrStatus) { this.ocrStatus = ocrStatus; }
    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
    public String getElapsed() { return elapsed; }
    public void setElapsed(String elapsed) { this.elapsed = elapsed; }
    public String getProjectSummary() { return projectSummary; }
    public void setProjectSummary(String projectSummary) { this.projectSummary = projectSummary; }
    public OcrReviewResponse.OcrSummary getOcrSummary() { return ocrSummary; }
    public void setOcrSummary(OcrReviewResponse.OcrSummary ocrSummary) { this.ocrSummary = ocrSummary; }

    // --- Phase 4 findings ---
    public List<Finding> getFindings() { return findings; }
    public void setFindings(List<Finding> findings) { this.findings = findings; }

    /**
     * 是否有行级评论数据（来自 OCR）。
     */
    public boolean hasOcrComments() {
        return ocrComments != null && !ocrComments.isEmpty();
    }
}
