package com.devops.ai.core.efficiency.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 同一个代码位置被不同开发者多次修改的记录。
 * 包含至少 2 条 ChangeRecord（按时间正序），以及 AI 对后续修改意图的分类。
 */
public class RepeatedChange {

    public enum Intent {
        /** 修复前一个开发者引入的错误 */
        FIX,
        /** 在前一个开发者的基础上做功能增强 */
        ENHANCE,
        /** 无法确定意图（AI 调用失败或判断不明确） */
        UNCERTAIN
    }

    public enum Confidence {
        HIGH, MEDIUM, LOW
    }

    private String filePath;
    /** 时间正序排列的修改记录，至少 2 条 */
    private List<ChangeRecord> records = new ArrayList<>();
    /** AI 分类结果 */
    private Intent intent = Intent.UNCERTAIN;
    private Confidence confidence = Confidence.LOW;
    /** AI 给出的判断理由 */
    private String aiReasoning;

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public List<ChangeRecord> getRecords() { return records; }
    public void setRecords(List<ChangeRecord> records) { this.records = records; }

    public Intent getIntent() { return intent; }
    public void setIntent(Intent intent) { this.intent = intent; }

    public Confidence getConfidence() { return confidence; }
    public void setConfidence(Confidence confidence) { this.confidence = confidence; }

    public String getAiReasoning() { return aiReasoning; }
    public void setAiReasoning(String aiReasoning) { this.aiReasoning = aiReasoning; }

    public ChangeRecord getFirstChange() {
        return records.isEmpty() ? null : records.get(0);
    }

    public ChangeRecord getLastChange() {
        return records.isEmpty() ? null : records.get(records.size() - 1);
    }

    public boolean isFix() { return intent == Intent.FIX; }
    public boolean isEnhance() { return intent == Intent.ENHANCE; }

    @Override
    public String toString() {
        return "RepeatedChange{" +
                "filePath='" + filePath + '\'' +
                ", recordCount=" + records.size() +
                ", intent=" + intent +
                ", confidence=" + confidence +
                '}';
    }
}
