package com.devops.ai.core.review.model;

public class GraphNode {
    private String id;
    private String type;
    private String name;
    private String filePath;
    private String changeType;
    private String packageName;

    public GraphNode() {}

    public GraphNode(String id, String type, String name, String filePath, String changeType) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.filePath = filePath;
        this.changeType = changeType;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
}
