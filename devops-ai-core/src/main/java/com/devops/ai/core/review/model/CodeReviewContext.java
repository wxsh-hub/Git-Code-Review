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
}
