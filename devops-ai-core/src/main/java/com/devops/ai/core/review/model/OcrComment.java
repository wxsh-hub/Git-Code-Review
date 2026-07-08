package com.devops.ai.core.review.model;

/**
 * 行级代码审查评论，对应 OCR code_scan 返回的 Comment 结构。
 */
public class OcrComment {
    private String path;           // 文件路径
    private String content;        // 评论内容
    private String category;       // 问题分类（SECURITY/NPE/...）— review LLM 输出
    private String existingCode;   // 现有代码
    private String suggestionCode; // 建议代码
    private int startLine;         // 起始行号
    private int endLine;           // 结束行号
    private String thinking;       // LLM 思考过程

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getExistingCode() { return existingCode; }
    public void setExistingCode(String existingCode) { this.existingCode = existingCode; }
    public String getSuggestionCode() { return suggestionCode; }
    public void setSuggestionCode(String suggestionCode) { this.suggestionCode = suggestionCode; }
    public int getStartLine() { return startLine; }
    public void setStartLine(int startLine) { this.startLine = startLine; }
    public int getEndLine() { return endLine; }
    public void setEndLine(int endLine) { this.endLine = endLine; }
    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }
}
