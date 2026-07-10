package com.devops.ai.core.review.ai;

import com.devops.ai.core.review.model.FilterResult;
import com.devops.ai.core.review.model.Finding;
import com.devops.ai.core.review.model.FindingCategory;
import com.devops.ai.core.review.model.FindingSeverity;
import com.devops.ai.core.review.model.FindingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Finding 二次校验器，执行四道把关：行号准确性、去重、误报检测、触发条件完整性。
 *
 * <p>管线上排在 Phase 6（review LLM 交叉验证）之后，
 * 使用双 LLM 交叉验证后的最终 confidence 做误报判定。</p>
 *
 * <p>Phase 4 管线适配：
 * <pre>{@code
 *   FilterResult fr = verifier.verify(findings, context.getRepoPath());
 *   return fr.toPipelineOutput(); // 返回 accepted + downgraded
 * }</pre></p>
 */
@Component
public class FindingVerifier {

    private static final Logger log = LoggerFactory.getLogger(FindingVerifier.class);

    // 误报检测：evidence 中是否已包含防护模式
    private static final Pattern TRY_CATCH = Pattern.compile("try\\s*\\{");
    private static final Pattern NULL_CHECK_THROW = Pattern.compile(
            "if\\s*\\(\\s*\\S+\\s*(==|!=)\\s*null\\s*\\)\\s*(\\{|throw)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTIONAL_OR_ELSE = Pattern.compile(
            "Optional\\.ofNullable|orElseThrow|orElseGet",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OBJECTS_REQUIRE = Pattern.compile(
            "Objects\\.requireNonNull|Preconditions\\.checkNotNull",
            Pattern.CASE_INSENSITIVE);

    // ================================================================
    // 主入口
    // ================================================================

    /**
     * 对 Finding 列表执行全部四道校验。
     *
     * @param findings 原始 Finding 列表
     * @param repoPath 仓库根路径（校验 1 读取文件行数，可为 null——null 时跳过行号越界检查）
     * @return FilterResult 分组结果
     */
    public FilterResult verify(List<Finding> findings, String repoPath) {
        FilterResult result = new FilterResult();

        if (findings == null || findings.isEmpty()) {
            return result;
        }

        // 校验 1: 行号准确性（可被 repoPath=null 跳过）
        List<Finding> afterLineCheck;
        if (repoPath != null && !repoPath.isEmpty()) {
            afterLineCheck = checkLineAccuracy(findings, repoPath, result);
        } else {
            afterLineCheck = findings;
        }

        // 校验 2: 去重
        List<Finding> afterDedup = deduplicate(afterLineCheck);

        // 校验 2.5: 编译错误过滤 — 代码能构建说明无编译错误，跨模块引用看不到是正常的
        List<Finding> afterCompileFilter = filterCompileErrors(afterDedup, result);

        // 校验 3: 误报检测
        // 校验 4: 触发条件完整性
        for (Finding f : afterCompileFilter) {
            Finding current = f;
            boolean falsePositive = checkFalsePositive(current);
            boolean triggerDowngraded = checkTriggerCompleteness(current);
            if (falsePositive || triggerDowngraded) {
                result.downgrade(current);
            } else {
                result.accept(current);
            }
        }

        log.info("FindingVerifier: {} → accepted: {}, rejected: {}, downgraded: {}",
                findings.size(),
                result.getAccepted().size(),
                result.getRejected().size(),
                result.getDowngraded().size());
        return result;
    }

    // ================================================================
    // 校验 1: 行号准确性
    // ================================================================

    /**
     * 检查行号合法性：
     * <ul>
     *   <li>startLine ≤ 0 → 拒绝</li>
     *   <li>endLine < startLine → 自动修正 endLine = startLine</li>
     *   <li>startLine > 文件总行数 → 拒绝</li>
     * </ul>
     */
    List<Finding> checkLineAccuracy(List<Finding> findings, String repoPath, FilterResult result) {
        List<Finding> valid = new ArrayList<>();
        for (Finding f : findings) {
            // startLine ≤ 0 → 拒绝
            if (f.getStartLine() <= 0) {
                log.warn("Finding rejected: startLine={} for file={}", f.getStartLine(), f.getFile());
                result.reject(f);
                continue;
            }
            // endLine < startLine → 自动修正
            if (f.getEndLine() < f.getStartLine()) {
                log.info("Finding auto-corrected: endLine={} → startLine={} for file={}",
                        f.getEndLine(), f.getStartLine(), f.getFile());
                f.setEndLine(f.getStartLine());
            }
            // 读取文件检查行号上界
            int totalLines = countFileLines(repoPath, f.getFile());
            if (totalLines > 0 && f.getStartLine() > totalLines) {
                log.warn("Finding rejected: startLine={} exceeds file total lines={} for {}",
                        f.getStartLine(), totalLines, f.getFile());
                result.reject(f);
                continue;
            }
            valid.add(f);
        }
        return valid;
    }

    // ================================================================
    // 校验 2: 去重
    // ================================================================

    /**
     * 同文件 + 行号范围重叠 → 合并为一个。
     * <ul>
     *   <li>合并时保留较严重的 severity</li>
     *   <li>合并 evidence / suggestedFix 文本（去重拼接）</li>
     *   <li>合并后 confidence 取最大值</li>
     * </ul>
     */
    List<Finding> deduplicate(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) return findings;

        // 按文件分组
        Map<String, List<Finding>> byFile = new LinkedHashMap<>();
        for (Finding f : findings) {
            String key = f.getFile() != null ? f.getFile() : "(unknown)";
            byFile.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }

        List<Finding> result = new ArrayList<>();
        for (Map.Entry<String, List<Finding>> entry : byFile.entrySet()) {
            result.addAll(mergeByFile(entry.getValue()));
        }
        return result;
    }

    private List<Finding> mergeByFile(List<Finding> fileFindings) {
        if (fileFindings.size() <= 1) return fileFindings;

        // 按 startLine 升序排列
        List<Finding> sorted = new ArrayList<>(fileFindings);
        sorted.sort(Comparator.comparingInt(Finding::getStartLine));

        List<Finding> merged = new ArrayList<>();
        Finding current = sorted.get(0);

        for (int i = 1; i < sorted.size(); i++) {
            Finding next = sorted.get(i);
            // 区间 [current.startLine, current.endLine] 与 [next.startLine, next.endLine] 有交集
            if (current.getEndLine() >= next.getStartLine()) {
                current = mergeTwo(current, next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    /** 合并两个重叠的 Finding */
    private Finding mergeTwo(Finding a, Finding b) {
        Finding merged = new Finding();
        // 基础信息取 a（先出现的）
        merged.setFile(a.getFile());
        merged.setStartLine(a.getStartLine());
        merged.setEndLine(Math.max(a.getEndLine(), b.getEndLine()));

        // severity 保留较严重的（序数越小越严重）
        merged.setSeverity(
                a.getSeverity().ordinal() <= b.getSeverity().ordinal()
                        ? a.getSeverity() : b.getSeverity());

        // category 取 confidence 较高的那个
        merged.setCategory(a.getConfidence() >= b.getConfidence()
                ? a.getCategory() : b.getCategory());

        // confidence 取最大值
        merged.setConfidence(Math.max(a.getConfidence(), b.getConfidence()));

        // status: CONFIRMED > FALSE_POSITIVE > UNREVIEWED
        if (a.getStatus() == FindingStatus.CONFIRMED || b.getStatus() == FindingStatus.CONFIRMED) {
            merged.setStatus(FindingStatus.CONFIRMED);
        } else if (a.getStatus() == FindingStatus.FALSE_POSITIVE || b.getStatus() == FindingStatus.FALSE_POSITIVE) {
            merged.setStatus(FindingStatus.FALSE_POSITIVE);
        } else {
            merged.setStatus(FindingStatus.UNREVIEWED);
        }

        // 文本字段去重拼接
        merged.setEvidence(mergeText(a.getEvidence(), b.getEvidence()));
        merged.setSuggestedFix(mergeText(a.getSuggestedFix(), b.getSuggestedFix()));
        merged.setTrigger(mergeText(a.getTrigger(), b.getTrigger()));
        merged.setReviewConclusion(mergeText(a.getReviewConclusion(), b.getReviewConclusion()));

        // blame 字段合并（Phase 5 填充）
        merged.setOwner(mergeText(a.getOwner(), b.getOwner()));
        merged.setOwnerEmail(mergeText(a.getOwnerEmail(), b.getOwnerEmail()));
        merged.setBlameCommitIds(mergeLists(a.getBlameCommitIds(), b.getBlameCommitIds()));
        merged.setCandidateHandler(mergeText(a.getCandidateHandler(), b.getCandidateHandler()));
        // TODO Phase 5: blameDetails (Map<String, BlameShare>) 合并 —— a 优先，同 key 用 a
        merged.setReviewer(a.getReviewer());         // Phase 6 填充，取先出现的
        merged.setModuleName(a.getModuleName());     // Phase 4 填充，同组内一定相同

        return merged;
    }

    /** 合并两段文本，去重：如果 b 的内容已经包含在 a 中，就跳过 */
    private String mergeText(String a, String b) {
        if (a == null || a.isEmpty()) return b;
        if (b == null || b.isEmpty()) return a;
        if (a.contains(b)) return a;
        if (b.contains(a)) return b;
        return a + "\n---\n" + b;
    }

    /** 合并两个列表，去重，a 优先 */
    private List<String> mergeLists(List<String> a, List<String> b) {
        List<String> result = new ArrayList<>(a);
        if (b != null) {
            for (String item : b) {
                if (!result.contains(item)) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    // ================================================================
    // 校验 2.5: 编译错误过滤
    // ================================================================

    /**
     * 过滤掉 COMPILE_ERROR 类别的 Finding。
     *
     * <p>代码能成功构建并部署，说明不存在编译错误。
     * 跨模块引用的类/方法/字段/Bean 在其他模块定义是正常的，
     * LLM 看不到其他模块代码时容易误判为编译错误。</p>
     *
     * @return 过滤后的 Finding 列表（不含 COMPILE_ERROR）
     */
    List<Finding> filterCompileErrors(List<Finding> findings, FilterResult result) {
        List<Finding> filtered = new ArrayList<>();
        for (Finding f : findings) {
            if (f.getCategory() == FindingCategory.COMPILE_ERROR) {
                log.info("Compile error filtered: file={}, lines={}-{}, evidence={}",
                        f.getFile(), f.getStartLine(), f.getEndLine(),
                        f.getEvidence() != null ? f.getEvidence().substring(0, Math.min(80, f.getEvidence().length())) : "(null)");
                result.reject(f);
            } else {
                filtered.add(f);
            }
        }
        return filtered;
    }

    // ================================================================
    // 校验 3: 误报检测
    // ================================================================

    /**
     * evidence 中已包含防护模式，且 confidence < 0.8 → 降级为 P3，标记 FALSE_POSITIVE。
     *
     * <p>检测 5 种防护模式：try-catch / @Valid / if-null-throw / Optional.orElseThrow / Objects.requireNonNull</p>
     *
     * @return true 如果该 Finding 被降级
     */
    boolean checkFalsePositive(Finding f) {
        String evidence = f.getEvidence();
        if (evidence == null || evidence.isEmpty()) return false;

        boolean hasProtection = false;
        // try-catch
        if (TRY_CATCH.matcher(evidence).find()) hasProtection = true;
        // @Valid / @Validated
        if (evidence.contains("@Valid")) hasProtection = true;
        // if-null-throw
        if (NULL_CHECK_THROW.matcher(evidence).find()) hasProtection = true;
        // Optional.orElseThrow
        if (OPTIONAL_OR_ELSE.matcher(evidence).find()) hasProtection = true;
        // Objects.requireNonNull
        if (OBJECTS_REQUIRE.matcher(evidence).find()) hasProtection = true;

        if (hasProtection && f.getConfidence() < 0.8) {
            log.info("False positive detected: file={}, lines={}-{}, confidence={}",
                    f.getFile(), f.getStartLine(), f.getEndLine(), f.getConfidence());
            f.setSeverity(FindingSeverity.LOW);           // P3
            f.setStatus(FindingStatus.FALSE_POSITIVE);
            return true;
        }
        return false;
    }

    // ================================================================
    // 校验 4: 触发条件完整性
    // ================================================================

    /**
     * P0/P1 问题缺少 trigger → 降级为 P2。
     * 确保高危问题都有可复现的场景描述。
     *
     * @return true 如果该 Finding 被降级
     */
    boolean checkTriggerCompleteness(Finding f) {
        if (!isHighSeverity(f.getSeverity())) return false;

        String trigger = f.getTrigger();
        if (trigger == null || trigger.trim().isEmpty()) {
            log.info("P0/P1 missing trigger: file={}, lines={}-{} → downgrading to P2",
                    f.getFile(), f.getStartLine(), f.getEndLine());
            f.setSeverity(FindingSeverity.MEDIUM);         // P2
            return true;
        }
        return false;
    }

    private boolean isHighSeverity(FindingSeverity s) {
        return s == FindingSeverity.BLOCKER || s == FindingSeverity.HIGH;
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /** 读取文件计算总行数，失败时返回 -1（跳过越界检查） */
    int countFileLines(String repoPath, String filePath) {
        if (repoPath == null || filePath == null) return -1;
        File file = new File(repoPath, filePath);
        if (!file.exists() || !file.isFile()) return -1;

        int lines = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            while (reader.readLine() != null) {
                lines++;
            }
        } catch (IOException e) {
            log.debug("Failed to count lines for {}: {}", filePath, e.getMessage());
            return -1;
        }
        return lines;
    }
}
