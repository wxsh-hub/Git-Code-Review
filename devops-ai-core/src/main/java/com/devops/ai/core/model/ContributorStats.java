package com.devops.ai.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ContributorStats {

    private String authorName;
    private String authorEmail;
    private int commitCount;
    private double percentage;
    private Map<String, Integer> categoryDistribution;
    private Map<String, Integer> commitFrequency;
    private boolean lowFrequency;
    private List<CommitDetail> commitDetails;

    public ContributorStats() {
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getAuthorEmail() {
        return authorEmail;
    }

    public void setAuthorEmail(String authorEmail) {
        this.authorEmail = authorEmail;
    }

    public int getCommitCount() {
        return commitCount;
    }

    public void setCommitCount(int commitCount) {
        this.commitCount = commitCount;
    }

    public double getPercentage() {
        return percentage;
    }

    public void setPercentage(double percentage) {
        this.percentage = percentage;
    }

    public Map<String, Integer> getCategoryDistribution() {
        return categoryDistribution;
    }

    public void setCategoryDistribution(Map<String, Integer> categoryDistribution) {
        this.categoryDistribution = categoryDistribution;
    }

    public Map<String, Integer> getCommitFrequency() {
        return commitFrequency;
    }

    public void setCommitFrequency(Map<String, Integer> commitFrequency) {
        this.commitFrequency = commitFrequency;
    }

    public boolean isLowFrequency() {
        return lowFrequency;
    }

    public void setLowFrequency(boolean lowFrequency) {
        this.lowFrequency = lowFrequency;
    }

    public List<CommitDetail> getCommitDetails() {
        return commitDetails;
    }

    public void setCommitDetails(List<CommitDetail> commitDetails) {
        this.commitDetails = commitDetails;
    }

    public static class CommitDetail {

        private String commitId;
        private String message;
        private String category;
        private String date;

        public CommitDetail() {
        }

        public String getCommitId() {
            return commitId;
        }

        public void setCommitId(String commitId) {
            this.commitId = commitId;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }
    }
}
