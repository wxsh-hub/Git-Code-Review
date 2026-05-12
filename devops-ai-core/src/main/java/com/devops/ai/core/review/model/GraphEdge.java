package com.devops.ai.core.review.model;

public class GraphEdge {
    private String sourceId;
    private String targetId;
    private String relationType;

    public GraphEdge() {}

    public GraphEdge(String sourceId, String targetId, String relationType) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.relationType = relationType;
    }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
}
