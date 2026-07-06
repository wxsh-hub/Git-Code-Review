package com.devops.ai.core.review.ai;

import com.devops.ai.core.review.model.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;

/**
 * 对代码审查发现的 P0/P1 漏洞做 git blame 追溯。
 *
 * <p>实现 {@link CodeReviewAiService.FindingPostProcessor} 接口，
 * 在审查后处理管线中排在 SecretDetector 之前执行。</p>
 *
 * <h3>三级回退策略</h3>
 * <ol>
 *   <li>git blame HEAD 查到作者 → owner = "张三(50%), 李四(50%)"</li>
 *   <li>blame 无结果 → git log -1 获取文件最后修改者 → candidateHandler = 修改者</li>
 *   <li>git log 也无结果 → candidateHandler = "待指派"</li>
 * </ol>
 *
 * <p>P2/P3/P4 级别的 Finding 不处理（直接透传）。</p>
 */
@Component
public class FindingBlameTracer {

    private static final Logger log = LoggerFactory.getLogger(FindingBlameTracer.class);

    // ================================================================
    // 主入口
    // ================================================================

    /**
     * 对 P0/P1 Finding 列表执行 git blame 追溯。
     *
     * @param findings 原始 Finding 列表
     * @param repoPath 仓库根路径（来自 CodeReviewContext.getRepoPath()）
     * @return 填充了 owner/ownerEmail/blameCommitIds/blameDetails/candidateHandler 的 Finding 列表
     */
    public List<Finding> trace(List<Finding> findings, String repoPath) {
        if (findings == null || findings.isEmpty()) return Collections.emptyList();
        if (repoPath == null || repoPath.isEmpty()) {
            log.warn("repoPath is null, skipping blame tracing for {} findings", findings.size());
            return findings;
        }

        File gitDir = new File(repoPath, ".git");
        if (!gitDir.exists()) {
            log.warn("No .git directory at {}, skipping blame tracing for {} findings", repoPath, findings.size());
            return findings;
        }

        int traced = 0;
        int fallback = 0;
        int skipped = 0;
        try (Git git = Git.open(new File(repoPath))) {
            ObjectId headId = git.getRepository().resolve("HEAD");
            if (headId == null) {
                log.warn("Cannot resolve HEAD in repo {}, skipping blame for all findings", repoPath);
                return findings;
            }

            for (Finding f : findings) {
                // 只处理 P0/P1
                if (!isHighSeverity(f.getSeverity())) {
                    f.setCandidateHandler("待指派");
                    skipped++;
                    continue;
                }

                try {
                    traceSingle(git, headId, f);
                    traced++;
                } catch (Exception e) {
                    log.warn("Blame failed for {} lines {}-{}: {}",
                            f.getFile(), f.getStartLine(), f.getEndLine(), e.getMessage());
                    fallbackToGitLog(git, f);
                    fallback++;
                }
            }
        } catch (Exception e) {
            log.error("Failed to open git repo at {}: {}", repoPath, e.getMessage());
        }

        log.info("Blame tracing complete: {} traced, {} fallback, {} skipped (P2-P4), {} total",
                traced, fallback, skipped, findings.size());
        return findings;
    }

    // ================================================================
    // 单条追溯
    // ================================================================

    /**
     * 对单条 P0/P1 Finding 执行 git blame。
     */
    private void traceSingle(Git git, ObjectId headId, Finding f) throws Exception {
        String filePath = f.getFile();
        int startLine = f.getStartLine();
        int endLine = f.getEndLine();

        if (filePath == null || filePath.isEmpty()) {
            log.debug("Finding {} has no file path, skipping blame", f.getId());
            fallbackToGitLog(git, f);
            return;
        }
        if (startLine <= 0) {
            log.debug("Finding {} has invalid startLine={}, skipping blame", f.getId(), startLine);
            fallbackToGitLog(git, f);
            return;
        }
        if (endLine < startLine) {
            endLine = startLine;
        }

        // 执行 git blame（HEAD 版本）
        org.eclipse.jgit.blame.BlameResult jgitBlame;
        try {
            jgitBlame = git.blame()
                    .setFilePath(filePath)
                    .setStartCommit(headId)
                    .setFollowFileRenames(true)
                    .call();
        } catch (Exception e) {
            log.debug("JGit blame failed for {}: {}", filePath, e.getMessage());
            fallbackToGitLog(git, f);
            return;
        }

        if (jgitBlame == null) {
            log.debug("JGit blame returned null for {}", filePath);
            fallbackToGitLog(git, f);
            return;
        }

        int totalLines = jgitBlame.getResultContents().size();
        if (totalLines == 0) {
            log.debug("File {} is empty, skipping blame", filePath);
            fallbackToGitLog(git, f);
            return;
        }

        // 调整行号范围到文件边界内
        int lineFrom = Math.max(1, startLine);
        int lineTo = Math.min(endLine, totalLines);

        // 逐行收集 blame 信息，按 commitId 分组
        Map<String, BlameLineInfo> byCommit = new LinkedHashMap<>();
        int blamedLines = 0;

        for (int line = lineFrom; line <= lineTo; line++) {
            int blameIdx = line - 1; // JGit blame 是 0-indexed
            if (blameIdx >= totalLines) break;

            try {
                PersonIdent author = jgitBlame.getSourceAuthor(blameIdx);
                RevCommit commit = jgitBlame.getSourceCommit(blameIdx);
                if (author != null && commit != null) {
                    String commitId = commit.getName();
                    BlameLineInfo info = byCommit.computeIfAbsent(commitId, k ->
                            new BlameLineInfo(commitId, commit.getShortMessage(),
                                    author.getName(), author.getEmailAddress(),
                                    author.getWhen() != null ? author.getWhen().getTime() : 0));
                    info.lineCount++;
                    blamedLines++;
                }
            } catch (Exception e) {
                log.trace("Blame line {} in {} failed: {}", line, filePath, e.getMessage());
            }
        }

        if (blamedLines == 0 || byCommit.isEmpty()) {
            log.debug("No blame results for {} lines {}-{}", filePath, startLine, endLine);
            fallbackToGitLog(git, f);
            return;
        }

        // 计算份额：按行数比例（张三 3行/4行=75%，李四 1行/4行=25%）

        // 填充 Finding 字段
        StringBuilder ownerBuilder = new StringBuilder();
        StringBuilder emailBuilder = new StringBuilder();
        List<String> commitIds = new ArrayList<>();
        Map<String, BlameShare> details = new LinkedHashMap<>();

        boolean first = true;
        for (BlameLineInfo info : byCommit.values()) {
            if (!first) {
                ownerBuilder.append(", ");
                emailBuilder.append(", ");
            }
            double share = info.lineCount / (double) blamedLines;
            String sharePct = String.format("%.0f", share * 100);
            ownerBuilder.append(info.authorName).append("(").append(sharePct).append("%)");
            emailBuilder.append(info.authorEmail != null ? info.authorEmail : "");

            commitIds.add(info.commitId);

            BlameShare bs = new BlameShare(
                    info.authorName, info.authorEmail,
                    info.commitId, info.commitMessage,
                    info.commitTime, share);
            details.put(info.commitId, bs);

            first = false;
        }

        f.setOwner(ownerBuilder.toString());
        f.setOwnerEmail(emailBuilder.toString());
        f.setBlameCommitIds(commitIds);
        f.setBlameDetails(details);
        f.setCandidateHandler(ownerBuilder.toString()); // 候选处理人 = blame owner

        log.debug("Blame {} lines {}-{}: {} author(s), {} blamed lines",
                filePath, startLine, endLine, byCommit.size(), blamedLines);
    }

    // ================================================================
    // 降级回退
    // ================================================================

    /**
     * 三级回退：git blame 无结果 → git log -1 获取文件最后修改者 → "待指派"。
     */
    private void fallbackToGitLog(Git git, Finding f) {
        String filePath = f.getFile();
        if (filePath == null || filePath.isEmpty()) {
            f.setCandidateHandler("待指派");
            return;
        }

        try {
            Iterable<RevCommit> logs = git.log()
                    .addPath(filePath)
                    .setMaxCount(1)
                    .call();
            RevCommit lastCommit = logs.iterator().next();
            if (lastCommit != null) {
                PersonIdent author = lastCommit.getAuthorIdent();
                String name = author != null ? author.getName() : null;
                if (name != null && !name.isEmpty()) {
                    f.setCandidateHandler(name);
                    log.debug("Fallback to git log: {} → last modifier = {}", filePath, name);
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("git log fallback failed for {}: {}", filePath, e.getMessage());
        }

        // 最终兜底
        f.setCandidateHandler("待指派");
        log.debug("All fallbacks exhausted for {} → '待指派'", filePath);
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private boolean isHighSeverity(FindingSeverity s) {
        return s == FindingSeverity.BLOCKER || s == FindingSeverity.HIGH;
    }

    // ================================================================
    // 内部辅助类
    // ================================================================

    /** blame 结果按 commit 聚合的中间数据 */
    private static class BlameLineInfo {
        final String commitId;
        final String commitMessage;
        final String authorName;
        final String authorEmail;
        final long commitTime;
        int lineCount;

        BlameLineInfo(String commitId, String commitMessage, String authorName,
                      String authorEmail, long commitTime) {
            this.commitId = commitId;
            this.commitMessage = commitMessage;
            this.authorName = authorName;
            this.authorEmail = authorEmail;
            this.commitTime = commitTime;
            this.lineCount = 0;
        }
    }
}
