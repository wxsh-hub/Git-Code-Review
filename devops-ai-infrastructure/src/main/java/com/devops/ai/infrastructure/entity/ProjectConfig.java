package com.devops.ai.infrastructure.entity;

import javax.persistence.*;
import java.util.Date;

@Entity
@Table(name = "project_config")
public class ProjectConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "project_code", length = 100, unique = true, nullable = false)
    private String projectCode;

    @Column(name = "project_id", length = 100)
    private String projectId;

    @Column(name = "default_branch", length = 100)
    private String defaultBranch;

    @Column(name = "template_name", length = 100)
    private String templateName;

    @Column(name = "gitlab_url", length = 255, nullable = false)
    private String gitlabUrl;

    @Column(name = "auth_type", length = 20, nullable = false)
    private String authType;

    @Column(name = "connect_mode", length = 20)
    private String connectMode = "api";

    @Column(name = "credentials", length = 500, nullable = false)
    private String credentials;

    @Column(name = "api_version", length = 10, nullable = false)
    private String apiVersion = "v4";

    @Column(name = "is_active")
    private Boolean active;

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
        if (projectCode == null || projectCode.trim().isEmpty()) {
            projectCode = generateCode(name);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Date();
        if (projectCode == null || projectCode.trim().isEmpty()) {
            projectCode = generateCode(name);
        }
    }

    public static String generateCode(String projectName) {
        if (projectName == null || projectName.trim().isEmpty()) {
            return "project-" + System.currentTimeMillis();
        }
        String code = projectName.trim()
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-zA-Z0-9\\-]", "")
                .toLowerCase()
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (code.length() < 2) {
            code = "project-" + System.currentTimeMillis();
        }
        return code;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public String getGitlabUrl() {
        return gitlabUrl;
    }

    public void setGitlabUrl(String gitlabUrl) {
        this.gitlabUrl = gitlabUrl;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getConnectMode() {
        return connectMode;
    }

    public void setConnectMode(String connectMode) {
        this.connectMode = connectMode;
    }

    public String getCredentials() {
        return credentials;
    }

    public void setCredentials(String credentials) {
        this.credentials = credentials;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
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
