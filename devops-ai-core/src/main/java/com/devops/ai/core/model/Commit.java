package com.devops.ai.core.model;

import java.util.Date;
import java.util.List;

public class Commit {

    private String id;
    private String message;
    private String authorName;
    private String authorEmail;
    private Date createdAt;
    private List<String> parentIds;

    public Commit() {
    }

    public Commit(String id, String message, String authorName, String authorEmail, Date createdAt) {
        this.id = id;
        this.message = message;
        this.authorName = authorName;
        this.authorEmail = authorEmail;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public List<String> getParentIds() {
        return parentIds;
    }

    public void setParentIds(List<String> parentIds) {
        this.parentIds = parentIds;
    }
}
