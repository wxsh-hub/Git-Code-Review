package com.devops.ai.core.review.model;

import java.util.List;

public class FileDiff {
    private String filePath;
    private String changeType;
    private String oldContent;
    private String newContent;
    private String unifiedDiff;
    private List<String> hunks;

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public String getOldContent() { return oldContent; }
    public void setOldContent(String oldContent) { this.oldContent = oldContent; }
    public String getNewContent() { return newContent; }
    public void setNewContent(String newContent) { this.newContent = newContent; }
    public String getUnifiedDiff() { return unifiedDiff; }
    public void setUnifiedDiff(String unifiedDiff) { this.unifiedDiff = unifiedDiff; }
    public List<String> getHunks() { return hunks; }
    public void setHunks(List<String> hunks) { this.hunks = hunks; }
}
