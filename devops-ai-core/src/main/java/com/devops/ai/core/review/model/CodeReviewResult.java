package com.devops.ai.core.review.model;

public class CodeReviewResult {
    private String changeSummary;
    private String architectureAnalysis;
    private String codeIssues;
    private String impactAnalysis;
    private String testSuggestions;
    private String conclusion;
    private String riskLevel;
    private String keyFindings;
    private String rawResponse;

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
}
