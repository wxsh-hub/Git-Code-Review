package com.devops.ai.core.model;

public class Branch {

    private String name;
    private String commitId;
    private boolean merged;
    private boolean isDefault;
    private boolean canPush;
    private boolean canMerge;

    public Branch() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCommitId() {
        return commitId;
    }

    public void setCommitId(String commitId) {
        this.commitId = commitId;
    }

    public boolean isMerged() {
        return merged;
    }

    public void setMerged(boolean merged) {
        this.merged = merged;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public boolean isCanPush() {
        return canPush;
    }

    public void setCanPush(boolean canPush) {
        this.canPush = canPush;
    }

    public boolean isCanMerge() {
        return canMerge;
    }

    public void setCanMerge(boolean canMerge) {
        this.canMerge = canMerge;
    }
}
