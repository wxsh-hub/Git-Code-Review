package com.devops.ai.core.review.model;

import com.devops.ai.core.model.Commit;

import java.util.Date;
import java.util.List;

public class CodeReviewContext {
    private String projectName;
    private String projectVersion;
    private String branch;
    private List<Commit> commits;
    private List<FileDiff> fileDiffs;
    private CodeReviewGraph graph;
    private String graphAnalysisJson;
    private String repoPath;        // 仓库本地路径（传给 OcrmcpClient）
    private String gitRemoteUrl;    // 远程仓库 URL（Phase 1 新增，Phase 8 拼接 codeLink）
    private String sinceHash;       // 审查起始 commit hash（用于 code_review_diff）
    private String untilHash;       // 审查结束 commit hash（用于 code_review_diff）

    // --- Phase 4 新增 ---
    private ReviewScope reviewScope;        // 审查范围枚举
    private String scopeDescription;        // 范围描述文本（如"自 2025-01-15 起共 78 个 commit"）
    private int commitCount;                // commit 数量
    private Date reviewDate;                // 审查日期（Phase 8 用于截止时间计算）
    private boolean useOcrDeepScan;         // 是否启用 OCR 深度扫描（code_scan 逐文件审查全部内容）

    // --- CRG 集成（Phase 2） ---
    private Object crgGlobalSummary;        // CrgModels.CrgGlobalSummary，全局摘要（~576 tokens）

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getProjectVersion() { return projectVersion; }
    public void setProjectVersion(String projectVersion) { this.projectVersion = projectVersion; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public List<Commit> getCommits() { return commits; }
    public void setCommits(List<Commit> commits) { this.commits = commits; }
    public List<FileDiff> getFileDiffs() { return fileDiffs; }
    public void setFileDiffs(List<FileDiff> fileDiffs) { this.fileDiffs = fileDiffs; }
    public CodeReviewGraph getGraph() { return graph; }
    public void setGraph(CodeReviewGraph graph) { this.graph = graph; }
    public String getGraphAnalysisJson() { return graphAnalysisJson; }
    public void setGraphAnalysisJson(String graphAnalysisJson) { this.graphAnalysisJson = graphAnalysisJson; }
    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }
    public String getGitRemoteUrl() { return gitRemoteUrl; }
    public void setGitRemoteUrl(String gitRemoteUrl) { this.gitRemoteUrl = gitRemoteUrl; }
    public String getSinceHash() { return sinceHash; }
    public void setSinceHash(String sinceHash) { this.sinceHash = sinceHash; }
    public String getUntilHash() { return untilHash; }
    public void setUntilHash(String untilHash) { this.untilHash = untilHash; }

    // --- Phase 4 新增 getter/setter ---
    public ReviewScope getReviewScope() { return reviewScope; }
    public void setReviewScope(ReviewScope reviewScope) { this.reviewScope = reviewScope; }
    public String getScopeDescription() { return scopeDescription; }
    public void setScopeDescription(String scopeDescription) { this.scopeDescription = scopeDescription; }
    public int getCommitCount() { return commitCount; }
    public void setCommitCount(int commitCount) { this.commitCount = commitCount; }
    public Date getReviewDate() { return reviewDate; }
    public void setReviewDate(Date reviewDate) { this.reviewDate = reviewDate; }
    public boolean isUseOcrDeepScan() { return useOcrDeepScan; }
    public void setUseOcrDeepScan(boolean useOcrDeepScan) { this.useOcrDeepScan = useOcrDeepScan; }

    // --- CRG 集成（Phase 2） getter/setter ---
    public Object getCrgGlobalSummary() { return crgGlobalSummary; }
    public void setCrgGlobalSummary(Object crgGlobalSummary) { this.crgGlobalSummary = crgGlobalSummary; }
}
