package com.devops.ai.core.efficiency.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 开发者效率分析的完整结果。
 */
public class EfficiencyAnalysisResult {

    private String projectName;
    private String branch;
    private String commitRange;
    private long analysisTimeMs;

    private List<DeveloperEfficiency> developerEfficiencies = new ArrayList<>();
    private List<RepeatedChange> repeatedChanges = new ArrayList<>();

    /** 生成的 Markdown 总结报告 */
    private String summaryReport;

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getCommitRange() { return commitRange; }
    public void setCommitRange(String commitRange) { this.commitRange = commitRange; }

    public long getAnalysisTimeMs() { return analysisTimeMs; }
    public void setAnalysisTimeMs(long analysisTimeMs) { this.analysisTimeMs = analysisTimeMs; }

    public List<DeveloperEfficiency> getDeveloperEfficiencies() { return developerEfficiencies; }
    public void setDeveloperEfficiencies(List<DeveloperEfficiency> developerEfficiencies) { this.developerEfficiencies = developerEfficiencies; }

    public List<RepeatedChange> getRepeatedChanges() { return repeatedChanges; }
    public void setRepeatedChanges(List<RepeatedChange> repeatedChanges) { this.repeatedChanges = repeatedChanges; }

    public String getSummaryReport() { return summaryReport; }
    public void setSummaryReport(String summaryReport) { this.summaryReport = summaryReport; }
}
