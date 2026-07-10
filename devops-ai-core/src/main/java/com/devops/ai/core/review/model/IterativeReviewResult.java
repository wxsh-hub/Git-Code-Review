package com.devops.ai.core.review.model;

import java.util.List;

/**
 * 迭代式深度扫描中 LLM 每轮输出的结果。
 *
 * <p>包含三个字段：
 * <ul>
 *   <li>{@code confirmed} — 本轮确认的 findings（程序缓存到本地）</li>
 *   <li>{@code grepRequests} — 需要查看外部代码的请求（下一轮执行）</li>
 *   <li>{@code done} — true 表示审查完毕，不需要更多上下文</li>
 * </ul>
 */
public class IterativeReviewResult {

    /** 本轮确认的 findings */
    private List<Finding> confirmed;

    /** 需要 grep/Read 的请求 */
    private List<GrepRequest> grepRequests;

    /** true = 审查完毕 */
    private boolean done;

    public IterativeReviewResult() {}

    public List<Finding> getConfirmed() { return confirmed; }
    public void setConfirmed(List<Finding> confirmed) { this.confirmed = confirmed; }

    public List<GrepRequest> getGrepRequests() { return grepRequests; }
    public void setGrepRequests(List<GrepRequest> grepRequests) { this.grepRequests = grepRequests; }

    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }
}
