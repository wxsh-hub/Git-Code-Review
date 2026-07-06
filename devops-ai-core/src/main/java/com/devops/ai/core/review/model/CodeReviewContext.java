package com.devops.ai.core.review.model;

import com.devops.ai.core.model.Commit;

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
}
