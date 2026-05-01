package com.devops.ai.core.classifier;

public class ClassificationResult {

    private String commitHash;
    private String originalMessage;
    private String category;
    private double confidence;
    private String source; // "ai" or "rule"
    private boolean corrected;

    public ClassificationResult() {
    }

    public ClassificationResult(String commitHash, String originalMessage, String category) {
        this.commitHash = commitHash;
        this.originalMessage = originalMessage;
        this.category = category;
        this.source = "rule";
        this.confidence = 1.0;
    }

    public String getCommitHash() {
        return commitHash;
    }

    public void setCommitHash(String commitHash) {
        this.commitHash = commitHash;
    }

    public String getOriginalMessage() {
        return originalMessage;
    }

    public void setOriginalMessage(String originalMessage) {
        this.originalMessage = originalMessage;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean isCorrected() {
        return corrected;
    }

    public void setCorrected(boolean corrected) {
        this.corrected = corrected;
    }
}
