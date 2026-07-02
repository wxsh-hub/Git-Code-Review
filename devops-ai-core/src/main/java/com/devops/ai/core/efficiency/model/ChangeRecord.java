package com.devops.ai.core.efficiency.model;

import java.util.Date;

/**
 * 记录一次代码修改操作——谁、什么时间、在哪个文件修改了哪些行。
 */
public class ChangeRecord {

    public enum ChangeType { ADD, MODIFY, DELETE }

    private String filePath;
    private int lineStart;
    private int lineEnd;
    private String authorName;
    private String authorEmail;
    private String commitId;
    private String commitMessage;
    private Date timestamp;
    private String diffSnippet;
    private ChangeType changeType;

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public int getLineStart() { return lineStart; }
    public void setLineStart(int lineStart) { this.lineStart = lineStart; }

    public int getLineEnd() { return lineEnd; }
    public void setLineEnd(int lineEnd) { this.lineEnd = lineEnd; }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public String getAuthorEmail() { return authorEmail; }
    public void setAuthorEmail(String authorEmail) { this.authorEmail = authorEmail; }

    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) { this.commitId = commitId; }

    public String getCommitMessage() { return commitMessage; }
    public void setCommitMessage(String commitMessage) { this.commitMessage = commitMessage; }

    public Date getTimestamp() { return timestamp; }
    public void setTimestamp(Date timestamp) { this.timestamp = timestamp; }

    public String getDiffSnippet() { return diffSnippet; }
    public void setDiffSnippet(String diffSnippet) { this.diffSnippet = diffSnippet; }

    public ChangeType getChangeType() { return changeType; }
    public void setChangeType(ChangeType changeType) { this.changeType = changeType; }

    public boolean isDelete() { return changeType == ChangeType.DELETE; }

    public boolean overlaps(ChangeRecord other) {
        if (!this.filePath.equals(other.filePath)) return false;
        return this.lineStart <= other.lineEnd && other.lineStart <= this.lineEnd;
    }

    @Override
    public String toString() {
        return "ChangeRecord{" +
                "filePath='" + filePath + '\'' +
                ", lines=[" + lineStart + "-" + lineEnd + ']' +
                ", author='" + authorName + '\'' +
                ", commit='" + commitId + '\'' +
                ", changeType=" + changeType +
                '}';
    }
}
