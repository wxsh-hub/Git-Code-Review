package com.devops.ai.core.efficiency.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个开发者的效率画像：
 * 引入的 bug 数（含份额）、修复的 bug 数、bug 率。
 *
 * <p>v2: 改为基于 AI 提交分类 + git blame 反向溯源，
 * 替代 v1 的全量 churn 检测 + LLM FIX/ENHANCE 分类。</p>
 */
public class DeveloperEfficiency {

    private String authorName;
    private String authorEmail;

    private int totalCommits;

    /** 引入的 bug 数（含分数份额，多个引入者时均摊） */
    private double bugsIntroduced;
    /** 修复别人的 bug 数 */
    private int fixesMade;
    /** bug 率 = bugsIntroduced / totalCommits（可能 > 1.0） */
    private double bugRate;

    // --- Phase 7: CONFIRMED 计数 ---
    /** 已确认的 bug 引入数（双 LLM 置信度 ≥ 0.7） */
    private int confirmedCount;
    /** 误报数（双 LLM 置信度 < 0.7） */
    private int falsePositiveCount;

    /** 引入的每个 bug 的详情 */
    private List<BugDetail> bugDetails = new ArrayList<>();
    /** 修复的每个 bug 的详情 */
    private List<FixDetail> fixDetails = new ArrayList<>();

    // ===== 内部类 =====

    /**
     * 开发者引入的一个 bug 详情。
     */
    public static class BugDetail {
        /** 引入 bug 的 commit id */
        private String commitId;
        /** 引入 bug 的 commit message */
        private String commitMessage;
        /** 引入时间 */
        private String createdAt;
        /** 引入者姓名（bug 本来是谁写的） */
        private String introducedBy;
        /** 涉及的文件 */
        private String filePath;
        /** 涉及的行数 */
        private int lineCount;
        /** 该开发者的责任份额（1/N，N = 该行其他引入者人数） */
        private double share;
        /** 修复者姓名 */
        private String fixedBy;
        /** 修复 commit id */
        private String fixedCommitId;
        /** 修复 commit message */
        private String fixedMessage;
        /** 修复时间 */
        private String fixedAt;

        // --- Phase 6: 双 LLM 交叉验证字段 ---
        /** 归因状态（Phase 6 review LLM 判定） */
        private String attributionStatus;  // "CONFIRMED" / "FALSE_POSITIVE"
        /** review LLM 的复核结论 */
        private String reviewConclusion;
        /** review LLM 的置信度 */
        private double reviewerConfidence;

        public String getCommitId() { return commitId; }
        public void setCommitId(String commitId) { this.commitId = commitId; }
        public String getCommitMessage() { return commitMessage; }
        public void setCommitMessage(String commitMessage) { this.commitMessage = commitMessage; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        public String getIntroducedBy() { return introducedBy; }
        public void setIntroducedBy(String introducedBy) { this.introducedBy = introducedBy; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public int getLineCount() { return lineCount; }
        public void setLineCount(int lineCount) { this.lineCount = lineCount; }
        public double getShare() { return share; }
        public void setShare(double share) { this.share = share; }
        public String getFixedBy() { return fixedBy; }
        public void setFixedBy(String fixedBy) { this.fixedBy = fixedBy; }
        public String getFixedCommitId() { return fixedCommitId; }
        public void setFixedCommitId(String fixedCommitId) { this.fixedCommitId = fixedCommitId; }
        public String getFixedMessage() { return fixedMessage; }
        public void setFixedMessage(String fixedMessage) { this.fixedMessage = fixedMessage; }
        public String getFixedAt() { return fixedAt; }
        public void setFixedAt(String fixedAt) { this.fixedAt = fixedAt; }

        // --- Phase 6 getters/setters ---
        public String getAttributionStatus() { return attributionStatus; }
        public void setAttributionStatus(String attributionStatus) { this.attributionStatus = attributionStatus; }
        public String getReviewConclusion() { return reviewConclusion; }
        public void setReviewConclusion(String reviewConclusion) { this.reviewConclusion = reviewConclusion; }
        public double getReviewerConfidence() { return reviewerConfidence; }
        public void setReviewerConfidence(double reviewerConfidence) { this.reviewerConfidence = reviewerConfidence; }

        public boolean isConfirmed() {
            return "CONFIRMED".equals(attributionStatus);
        }
    }

    /**
     * 开发者修复的一个 bug 详情。
     */
    public static class FixDetail {
        /** fix commit id */
        private String commitId;
        /** fix commit message */
        private String commitMessage;
        /** 修复时间 */
        private String createdAt;
        /** 被修的人（bug 引入者） */
        private String introducedBy;
        /** bug 引入的 commit id */
        private String introducedByCommitId;
        /** bug 引入的 commit message */
        private String introducedByMessage;
        /** 涉及的文件 */
        private String filePath;

        public String getCommitId() { return commitId; }
        public void setCommitId(String commitId) { this.commitId = commitId; }
        public String getCommitMessage() { return commitMessage; }
        public void setCommitMessage(String commitMessage) { this.commitMessage = commitMessage; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        public String getIntroducedBy() { return introducedBy; }
        public void setIntroducedBy(String introducedBy) { this.introducedBy = introducedBy; }
        public String getIntroducedByCommitId() { return introducedByCommitId; }
        public void setIntroducedByCommitId(String introducedByCommitId) { this.introducedByCommitId = introducedByCommitId; }
        public String getIntroducedByMessage() { return introducedByMessage; }
        public void setIntroducedByMessage(String introducedByMessage) { this.introducedByMessage = introducedByMessage; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
    }

    // ===== getters / setters =====

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public String getAuthorEmail() { return authorEmail; }
    public void setAuthorEmail(String authorEmail) { this.authorEmail = authorEmail; }

    public int getTotalCommits() { return totalCommits; }
    public void setTotalCommits(int totalCommits) { this.totalCommits = totalCommits; }

    public double getBugsIntroduced() { return bugsIntroduced; }
    public void setBugsIntroduced(double bugsIntroduced) { this.bugsIntroduced = bugsIntroduced; }

    public int getFixesMade() { return fixesMade; }
    public void setFixesMade(int fixesMade) { this.fixesMade = fixesMade; }

    public double getBugRate() { return bugRate; }
    public void setBugRate(double bugRate) { this.bugRate = bugRate; }

    // --- Phase 7 CONFIRMED counts ---
    public int getConfirmedCount() { return confirmedCount; }
    public void setConfirmedCount(int confirmedCount) { this.confirmedCount = confirmedCount; }
    public int getFalsePositiveCount() { return falsePositiveCount; }
    public void setFalsePositiveCount(int falsePositiveCount) { this.falsePositiveCount = falsePositiveCount; }

    public List<BugDetail> getBugDetails() { return bugDetails; }
    public void setBugDetails(List<BugDetail> bugDetails) { this.bugDetails = bugDetails; }

    public List<FixDetail> getFixDetails() { return fixDetails; }
    public void setFixDetails(List<FixDetail> fixDetails) { this.fixDetails = fixDetails; }
}
