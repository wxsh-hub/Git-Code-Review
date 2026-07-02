package com.devops.ai.core.efficiency.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 开发者效率分析的完整结果。
 *
 * <p>v2: 基于 AI 提交分类 + git blame 反向溯源，
 * 替代 v1 的全量 churn 检测 + LLM 分类。</p>
 */
public class EfficiencyAnalysisResult {

    private String projectName;
    private String branch;
    private String commitRange;
    private long analysisTimeMs;

    private List<DeveloperEfficiency> developerEfficiencies = new ArrayList<>();

    /** 所有 bug 详情（跨开发者汇总，供报告使用） */
    private List<DeveloperEfficiency.BugDetail> allBugDetails = new ArrayList<>();

    /** fix commit 总数 */
    private int totalFixCommits;

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

    public List<DeveloperEfficiency.BugDetail> getAllBugDetails() { return allBugDetails; }
    public void setAllBugDetails(List<DeveloperEfficiency.BugDetail> allBugDetails) { this.allBugDetails = allBugDetails; }

    public int getTotalFixCommits() { return totalFixCommits; }
    public void setTotalFixCommits(int totalFixCommits) { this.totalFixCommits = totalFixCommits; }

    public String getSummaryReport() { return summaryReport; }
    public void setSummaryReport(String summaryReport) { this.summaryReport = summaryReport; }
}
