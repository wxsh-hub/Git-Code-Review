package com.devops.ai.core.efficiency.model;

/**
 * Diff 中单个 hunk 的结构化表示。
 * 包含行号范围、变更内容和周围的上下文代码。
 */
public class DiffHunk {

    private String filePath;
    private int oldStart;
    private int oldCount;
    private int newStart;
    private int newCount;
    /** 修改行的前后各 5 行上下文 */
    private String contextBefore;
    /** 实际变更的内容（diff 中的 + 和 - 行） */
    private String changeContent;
    /** 修改行后面的上下文 */
    private String contextAfter;
    /** 原始 unified diff hunk header + body */
    private String rawHunk;

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public int getOldStart() { return oldStart; }
    public void setOldStart(int oldStart) { this.oldStart = oldStart; }

    public int getOldCount() { return oldCount; }
    public void setOldCount(int oldCount) { this.oldCount = oldCount; }

    public int getNewStart() { return newStart; }
    public void setNewStart(int newStart) { this.newStart = newStart; }

    public int getNewCount() { return newCount; }
    public void setNewCount(int newCount) { this.newCount = newCount; }

    public String getContextBefore() { return contextBefore; }
    public void setContextBefore(String contextBefore) { this.contextBefore = contextBefore; }

    public String getChangeContent() { return changeContent; }
    public void setChangeContent(String changeContent) { this.changeContent = changeContent; }

    public String getContextAfter() { return contextAfter; }
    public void setContextAfter(String contextAfter) { this.contextAfter = contextAfter; }

    public String getRawHunk() { return rawHunk; }
    public void setRawHunk(String rawHunk) { this.rawHunk = rawHunk; }

    /**
     * 返回 new 文件中受影响的起始行号。
     */
    public int getNewLineStart() { return newStart; }

    /**
     * 返回 new 文件中受影响的结束行号。
     */
    public int getNewLineEnd() {
        return newStart + Math.max(newCount - 1, 0);
    }
}
