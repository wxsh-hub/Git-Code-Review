package com.devops.ai.core.model;

import java.util.ArrayList;
import java.util.List;

public class Category {

    private String name;
    private List<Commit> commits;

    public Category() {
    }

    public Category(String name) {
        this.name = name;
        this.commits = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Commit> getCommits() {
        return commits;
    }

    public void setCommits(List<Commit> commits) {
        this.commits = commits;
    }

    public void addCommit(Commit commit) {
        if (this.commits == null) {
            this.commits = new ArrayList<>();
        }
        this.commits.add(commit);
    }
}
