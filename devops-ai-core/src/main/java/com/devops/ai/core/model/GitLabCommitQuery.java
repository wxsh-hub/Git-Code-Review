package com.devops.ai.core.model;

import java.util.Date;

public class GitLabCommitQuery {

    private String projectId;
    private String branch;
    private Date since;
    private Date until;
    private String author;
    private String sinceHash;
    private String untilHash;
    private int perPage = 100;
    private int page = 1;

    public GitLabCommitQuery() {
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public Date getSince() {
        return since;
    }

    public void setSince(Date since) {
        this.since = since;
    }

    public Date getUntil() {
        return until;
    }

    public void setUntil(Date until) {
        this.until = until;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
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

    public int getPerPage() {
        return perPage;
    }

    public void setPerPage(int perPage) {
        this.perPage = perPage;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }
}
