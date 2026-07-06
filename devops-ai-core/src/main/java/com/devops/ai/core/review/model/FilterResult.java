package com.devops.ai.core.review.model;

import java.util.ArrayList;
import java.util.List;

/**
 * FindingVerifier 的校验输出，将输入 Finding 分为三组。
 *
 * <p>Phase 4 管线适配：{@code accepted + downgraded = 管线输出的 List<Finding>}，
 * rejected 记录日志后丢弃。</p>
 */
public class FilterResult {

    /** 通过全部四道校验，可直接进入报告 */
    private final List<Finding> accepted = new ArrayList<>();

    /** 被拒绝（行号越界等严重问题），不进报告 */
    private final List<Finding> rejected = new ArrayList<>();

    /** 被降级但保留（误报降为 P3 / 缺 trigger 降为 P2），仍进报告但严重度降低 */
    private final List<Finding> downgraded = new ArrayList<>();

    public void accept(Finding f) { accepted.add(f); }
    public void reject(Finding f) { rejected.add(f); }
    public void downgrade(Finding f) { downgraded.add(f); }

    public List<Finding> getAccepted() { return accepted; }
    public List<Finding> getRejected() { return rejected; }
    public List<Finding> getDowngraded() { return downgraded; }

    /** 管线输出：已接受 + 已降级（跳过被拒绝的） */
    public List<Finding> toPipelineOutput() {
        List<Finding> out = new ArrayList<>(accepted.size() + downgraded.size());
        out.addAll(accepted);
        out.addAll(downgraded);
        return out;
    }
}
