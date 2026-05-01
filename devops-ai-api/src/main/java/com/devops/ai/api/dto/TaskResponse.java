package com.devops.ai.api.dto;

public class TaskResponse {

    private String taskId;
    private String status;
    private String estimatedTime;

    public TaskResponse() {
    }

    public TaskResponse(String taskId, String status, String estimatedTime) {
        this.taskId = taskId;
        this.status = status;
        this.estimatedTime = estimatedTime;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEstimatedTime() {
        return estimatedTime;
    }

    public void setEstimatedTime(String estimatedTime) {
        this.estimatedTime = estimatedTime;
    }
}
