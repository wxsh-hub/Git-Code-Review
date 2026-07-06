package com.devops.ai.core.review.model;

/**
 * 按 commit 聚合的 blame 份额。
 *
 * <p>Phase 5 — 同一个 Finding 涉及的一段代码可能由多人在不同 commit 中编写，
 * 按行数比例计算每人/每个 commit 的 blame 份额。</p>
 *
 * <p>设计约定：key = commitId（Finding.blameDetails 的 Map key），
 * 同一 commit 由同一人创建，所以 share 是该作者对该段代码的贡献比例 (0.0~1.0)。</p>
 */
public class BlameShare {

    /** 作者名 */
    private String authorName;

    /** 作者邮箱 */
    private String authorEmail;

    /** commit id */
    private String commitId;

    /** commit message */
    private String commitMessage;

    /** commit 时间（epoch 毫秒） */
    private long commitTime;

    /** blame 份额 0.0~1.0 */
    private double share;

    public BlameShare() {
    }

    public BlameShare(String authorName, String authorEmail, String commitId,
                      String commitMessage, long commitTime, double share) {
        this.authorName = authorName;
        this.authorEmail = authorEmail;
        this.commitId = commitId;
        this.commitMessage = commitMessage;
        this.commitTime = commitTime;
        this.share = share;
    }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public String getAuthorEmail() { return authorEmail; }
    public void setAuthorEmail(String authorEmail) { this.authorEmail = authorEmail; }

    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) { this.commitId = commitId; }

    public String getCommitMessage() { return commitMessage; }
    public void setCommitMessage(String commitMessage) { this.commitMessage = commitMessage; }

    public long getCommitTime() { return commitTime; }
    public void setCommitTime(long commitTime) { this.commitTime = commitTime; }

    public double getShare() { return share; }
    public void setShare(double share) { this.share = share; }
}
