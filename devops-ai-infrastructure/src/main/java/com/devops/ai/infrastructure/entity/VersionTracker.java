package com.devops.ai.infrastructure.entity;

import javax.persistence.*;
import java.util.Date;

@Entity
@Table(name = "version_tracker")
public class VersionTracker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", length = 100, nullable = false)
    private String projectId;

    @Column(name = "branch", length = 100, nullable = false)
    private String branch;

    @Column(name = "last_hash", length = 100, nullable = false)
    private String lastHash;

    @Column(name = "last_generated")
    @Temporal(TemporalType.TIMESTAMP)
    private Date lastGenerated;

    @Column(name = "history_json", columnDefinition = "TEXT")
    private String historyJson;

    @Column(name = "created_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt;

    @Column(name = "updated_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Date();
        updatedAt = new Date();
        if (lastGenerated == null) {
            lastGenerated = new Date();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Date();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getLastHash() {
        return lastHash;
    }

    public void setLastHash(String lastHash) {
        this.lastHash = lastHash;
    }

    public Date getLastGenerated() {
        return lastGenerated;
    }

    public void setLastGenerated(Date lastGenerated) {
        this.lastGenerated = lastGenerated;
    }

    public String getHistoryJson() {
        return historyJson;
    }

    public void setHistoryJson(String historyJson) {
        this.historyJson = historyJson;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}
