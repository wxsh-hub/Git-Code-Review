package com.devops.ai.core.review.model;

import com.devops.ai.core.efficiency.model.DeveloperEfficiency;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 全系统统一的代码问题数据模型。
 *
 * <p>Phase 1 数据地基 — OCR MCP、旧 LLM、Bug 归因、Secret 检测四个来源的问题
 * 都收敛到这一个数据结构，消除 OcrComment / CodeReviewResult / BugDetail 各自为政的问题。</p>
 *
 * <h3>字段来源</h3>
 * <ul>
 *   <li>Core 字段（id～status）：Phase 1 建立，各来源转换时尽力填充</li>
 *   <li>处置字段（codeLink～deadline）：Phase 8 使用，由各前置 Phase 填充</li>
 *   <li>模块归属（moduleName）：Phase 4 填充</li>
 *   <li>Blame 扩展字段：Phase 5 新增</li>
 * </ul>
 */
public class Finding {

    // ================================================================
    // 敏感信息脱敏正则（sanitize 使用）
    // ================================================================

    private static final Pattern[] SANITIZE_PATTERNS = {
            Pattern.compile("(password|passwd|pwd)\\s*[:=]\\s*\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(secret|secretKey|secret_key)\\s*[:=]\\s*\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(apiKey|api_key|apikey)\\s*[:=]\\s*\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(token|accessToken|access_token)\\s*[:=]\\s*\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(privateKey|private_key|privatekey)\\s*[:=]\\s*\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("-----BEGIN\\s*(RSA |EC |DSA |OPENSSH |)PRIVATE KEY-----.*?-----END\\s*\\1?PRIVATE KEY-----", Pattern.DOTALL),
            Pattern.compile("(jdbc|mongodb|redis|mysql)://[^\\s@]+:[^\\s@]+@", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(Authorization|Bearer)\\s+[\\w.\\-]+", Pattern.CASE_INSENSITIVE),
    };

    // ================================================================
    // Core 字段（id ～ status）
    // ================================================================

    /** UUID 短码，唯一标识 */
    private String id;

    /** 文件路径 */
    private String file;

    /** 起始行号（1-indexed） */
    private int startLine;

    /** 结束行号（1-indexed） */
    private int endLine;

    /** 严重级别 */
    private FindingSeverity severity;

    /** 问题分类 */
    private FindingCategory category;

    /** 最终置信度 0.0-1.0（Phase 6 复核后为双 LLM 平均） */
    private double confidence;

    /** 第二个 LLM 的复核结论（Phase 6 交叉验证时填充） */
    private String reviewConclusion;

    /** 问题所在的代码片段 */
    private String evidence;

    /** 什么输入/场景触发此问题 */
    private String trigger;

    /** 建议修复方式 */
    private String suggestedFix;

    /** 责任人（来自 git blame，Phase 5 填充） */
    private String owner;

    /** 责任人邮箱（Phase 5 填充） */
    private String ownerEmail;

    /** 追溯到的 commit id 列表（Phase 5 填充） */
    private List<String> blameCommitIds;

    /** 确认状态（Phase 6 复核后赋值） */
    private FindingStatus status;

    // ================================================================
    // 问题处置字段（Phase 8 使用，由各前置 Phase 填充）
    // ================================================================

    /** 代码链接（Phase 8 从 CodeReviewContext 拼接） */
    private String codeLink;

    /** 候选处理人（Phase 5 blame 追溯时填充，取自 owner） */
    private String candidateHandler;

    /** 复核人标识（Phase 6 交叉验证时填充，值为 "review LLM"） */
    private String reviewer;

    /** 建议修复截止时间（Phase 8 计算） */
    private String deadline;

    // ================================================================
    // 模块归属（Phase 4 填充）
    // ================================================================

    /** 所属业务模块名（Phase 4 分组时由 ReviewGroup.name 填充） */
    private String moduleName;

    // ================================================================
    // Blame 扩展字段（Phase 5 填充）
    // ================================================================

    /** blame 归属详情，key = commitId（Phase 5 填充） */
    private java.util.Map<String, BlameShare> blameDetails;

    // ================================================================
    // 构造器
    // ================================================================

    public Finding() {
        this.id = generateId();
        this.blameCommitIds = new ArrayList<>();
    }

    // ================================================================
    // 静态工厂方法
    // ================================================================

    /**
     * 从 id 后缀创建（用于 JSON 反序列化后重建 id）。
     */
    public static Finding withId(String id) {
        Finding f = new Finding();
        if (id != null && !id.isEmpty()) {
            f.id = id;
        }
        return f;
    }

    /**
     * OCR MCP 行级评论 → Finding。
     *
     * <p>映射关系：
     * <ul>
     *   <li>path → file</li>
     *   <li>startLine/endLine → 直接映射</li>
     *   <li>existingCode → evidence</li>
     *   <li>suggestionCode → suggestedFix</li>
     *   <li>content → 用于推断 severity/category</li>
     *   <li>thinking → reviewConclusion（OCR 自身的思考链作为初版复核）</li>
     * </ul>
     *
     * <p>Phase 2 会通过 LLM 结构化输出提供精确的 severity/category/confidence，
     * 当前使用关键词推断作为 fallback。</p>
     */
    public static Finding fromOcrComment(OcrComment oc) {
        Finding f = new Finding();
        f.file = oc.getPath();
        f.startLine = oc.getStartLine();
        f.endLine = oc.getEndLine();
        f.evidence = oc.getExistingCode();
        f.suggestedFix = oc.getSuggestionCode();
        f.reviewConclusion = oc.getThinking();

        // 从评论内容推断严重级别和分类（Phase 2 会通过 LLM 输出替换）
        String content = oc.getContent();
        if (content != null) {
            f.trigger = content; // OCR 的 content 描述问题本身
            f.severity = FindingSeverity.fromDescription(content);
            f.category = FindingCategory.fromDescription(content);
        }
        // OCR 初始置信度（未经复核，故意用非整数避免取整效果）
        f.confidence = 0.73;
        f.status = FindingStatus.UNREVIEWED; // 等待 Phase 6 交叉验证

        return f;
    }

    /**
     * 旧 LLM 审查结果 → Finding（摘要级）。
     *
     * <p>旧 LLM 输出是段落文本而非结构化行级数据，因此本方法生成一个摘要级 Finding，
     * 不建议将其与行级 Finding 直接混合。对于 OCR 行级数据，请使用
     * {@link #fromOcrComment(OcrComment)} 逐条转换。</p>
     *
     * <p>映射关系：
     * <ul>
     *   <li>keyFindings → evidence（关键发现作为证据）</li>
     *   <li>codeIssues → suggestedFix（缺陷描述作为修复上下文）</li>
     *   <li>riskLevel → severity 推断</li>
     *   <li>conclusion → reviewConclusion</li>
     * </ul>
     */
    public static Finding fromCodeReviewResult(CodeReviewResult result) {
        Finding f = new Finding();
        f.evidence = result.getKeyFindings();
        f.suggestedFix = result.getCodeIssues();
        f.reviewConclusion = result.getConclusion();
        f.confidence = 0.70;

        // 从 riskLevel 文本推断严重级别
        String riskLevel = result.getRiskLevel();
        if (riskLevel != null) {
            String rl = riskLevel.trim();
            if (rl.contains("高") || rl.contains("high")) {
                f.severity = FindingSeverity.HIGH;
            } else if (rl.contains("中") || rl.contains("medium")) {
                f.severity = FindingSeverity.MEDIUM;
            } else {
                f.severity = FindingSeverity.LOW;
            }
        } else {
            f.severity = FindingSeverity.INFO;
        }

        // 从 keyFindings 文本推断分类
        f.category = FindingCategory.fromDescription(result.getKeyFindings());
        f.status = FindingStatus.UNREVIEWED; // 等待 Phase 6 交叉验证

        return f;
    }

    /**
     * 效率分析 Bug 归因 → Finding。
     *
     * <p>BugDetail 携带的是"谁引入了这个 bug、被谁修了"的归因语义，
     * 转换为 Finding 时主要填充 blame 相关字段，代码质量相关字段留空
     * （Bug 的具体代码缺陷信息在 DeveloperEfficiencyService 中没有存储）。</p>
     *
     * <p>映射关系：
     * <ul>
     *   <li>filePath → file</li>
     *   <li>introducedBy → owner</li>
     *   <li>commitId → blameCommitIds[0]</li>
     *   <li>commitMessage → evidence（作为上下文）</li>
     * </ul>
     */
    public static Finding fromBugDetail(DeveloperEfficiency.BugDetail bug) {
        Finding f = new Finding();
        f.file = bug.getFilePath();
        f.owner = bug.getIntroducedBy();
        f.evidence = bug.getCommitMessage();
        f.suggestedFix = bug.getFixedMessage();       // 修复信息作为建议
        f.reviewConclusion = "修复者: " + (bug.getFixedBy() != null ? bug.getFixedBy() : "未知")
                + ", 修复于: " + (bug.getFixedAt() != null ? bug.getFixedAt() : "未知");
        f.confidence = 1.0;                            // 已由实际修复验证，置信度高
        f.severity = FindingSeverity.fromDescription(bug.getCommitMessage());
        f.category = FindingCategory.LOGIC_ERROR;      // Bug 归因默认归类为逻辑错误
        f.status = FindingStatus.UNREVIEWED;           // 等待 Phase 6 交叉验证

        if (bug.getCommitId() != null) {
            f.blameCommitIds.add(bug.getCommitId());
        }
        if (bug.getLineCount() > 0) {
            f.startLine = 1;
            f.endLine = bug.getLineCount();
        }

        return f;
    }

    /**
     * 生成 8 位 UUID 短码。
     */
    public static String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // ================================================================
    // 实例方法
    // ================================================================

    /**
     * 对所有文本字段做敏感信息脱敏。
     *
     * <p>覆盖范围：密码、Token、API Key、密钥、JDBC 连接串、Authorization 头。
     * 替换策略：保留前缀标识（如 "password=***"），不暴露实际值。</p>
     */
    public void sanitize() {
        this.evidence = sanitizeText(this.evidence);
        this.suggestedFix = sanitizeText(this.suggestedFix);
        this.trigger = sanitizeText(this.trigger);
        this.reviewConclusion = sanitizeText(this.reviewConclusion);
    }

    private static String sanitizeText(String text) {
        if (text == null || text.isEmpty()) return text;
        for (Pattern p : SANITIZE_PATTERNS) {
            java.util.regex.Matcher m = p.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String matched = m.group();
                String replacement;
                // 保留 key= 前缀，替换值为 ***
                int eqIdx = matched.indexOf('=');
                if (eqIdx > 0) {
                    replacement = matched.substring(0, eqIdx + 1) + "***";
                } else {
                    int colonIdx = matched.indexOf(':');
                    if (colonIdx > 0) {
                        replacement = matched.substring(0, colonIdx + 1) + "***";
                    } else {
                        // URL 凭据格式 user:pass@host
                        int atIdx = matched.lastIndexOf('@');
                        if (atIdx > 0 && matched.contains("://")) {
                            String prefix = matched.substring(0, matched.indexOf("://") + 3);
                            replacement = prefix + "***:***@";
                        } else {
                            // Authorization/Bearer 头
                            String lower = matched.toLowerCase();
                            if (lower.startsWith("authorization") || lower.startsWith("bearer")) {
                                int spaceIdx = matched.indexOf(' ');
                                if (spaceIdx > 0) {
                                    replacement = matched.substring(0, spaceIdx + 1) + "***";
                                } else {
                                    replacement = "***";
                                }
                            } else {
                                replacement = "***";
                            }
                        }
                    }
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            text = sb.toString();
        }
        return text;
    }

    // ================================================================
    // Getters / Setters
    // ================================================================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }

    public int getStartLine() { return startLine; }
    public void setStartLine(int startLine) { this.startLine = startLine; }

    public int getEndLine() { return endLine; }
    public void setEndLine(int endLine) { this.endLine = endLine; }

    public FindingSeverity getSeverity() { return severity; }
    public void setSeverity(FindingSeverity severity) { this.severity = severity; }

    public FindingCategory getCategory() { return category; }
    public void setCategory(FindingCategory category) { this.category = category; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getReviewConclusion() { return reviewConclusion; }
    public void setReviewConclusion(String reviewConclusion) { this.reviewConclusion = reviewConclusion; }

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }

    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }

    public String getSuggestedFix() { return suggestedFix; }
    public void setSuggestedFix(String suggestedFix) { this.suggestedFix = suggestedFix; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getOwnerEmail() { return ownerEmail; }
    public void setOwnerEmail(String ownerEmail) { this.ownerEmail = ownerEmail; }

    public List<String> getBlameCommitIds() { return new ArrayList<>(blameCommitIds); }
    public void setBlameCommitIds(List<String> blameCommitIds) { this.blameCommitIds = blameCommitIds; }

    public FindingStatus getStatus() { return status; }
    public void setStatus(FindingStatus status) { this.status = status; }

    public String getCodeLink() { return codeLink; }
    public void setCodeLink(String codeLink) { this.codeLink = codeLink; }

    public String getCandidateHandler() { return candidateHandler; }
    public void setCandidateHandler(String candidateHandler) { this.candidateHandler = candidateHandler; }

    public String getReviewer() { return reviewer; }
    public void setReviewer(String reviewer) { this.reviewer = reviewer; }

    public String getDeadline() { return deadline; }
    public void setDeadline(String deadline) { this.deadline = deadline; }

    public String getModuleName() { return moduleName; }
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }

    // --- Phase 5 blameDetails ---
    public java.util.Map<String, BlameShare> getBlameDetails() { return blameDetails; }
    public void setBlameDetails(java.util.Map<String, BlameShare> blameDetails) { this.blameDetails = blameDetails; }
}
