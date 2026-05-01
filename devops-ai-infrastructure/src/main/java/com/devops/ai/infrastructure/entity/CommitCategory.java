package com.devops.ai.infrastructure.entity;

import javax.persistence.*;

@Entity
@Table(name = "commit_category")
public class CommitCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_name", length = 50, nullable = false)
    private String categoryName;

    @Column(name = "prefix_patterns", length = 500)
    private String prefixPatterns;

    @Column(name = "keyword_patterns", length = 500)
    private String keywordPatterns;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "is_ai_enabled")
    private Boolean aiEnabled;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getPrefixPatterns() {
        return prefixPatterns;
    }

    public void setPrefixPatterns(String prefixPatterns) {
        this.prefixPatterns = prefixPatterns;
    }

    public String getKeywordPatterns() {
        return keywordPatterns;
    }

    public void setKeywordPatterns(String keywordPatterns) {
        this.keywordPatterns = keywordPatterns;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Boolean getAiEnabled() {
        return aiEnabled;
    }

    public void setAiEnabled(Boolean aiEnabled) {
        this.aiEnabled = aiEnabled;
    }
}
