package com.devops.ai.core.efficiency.detector;

import com.devops.ai.core.efficiency.model.ChangeRecord;
import com.devops.ai.core.efficiency.model.DiffHunk;
import com.devops.ai.core.efficiency.model.RepeatedChange;
import com.devops.ai.core.model.Commit;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 核心算法：使用 JGit 遍历提交历史，按行追踪代码所有权，
 * 检测同一代码位置被不同开发者修改的情况。
 *
 * 算法流程：
 * 1. 克隆/拉取仓库
 * 2. 从 sinceHash 到 untilHash，遍历所有非合并提交（时间正序）
 * 3. 对每个提交，获取父提交到当前提交的 tree diff
 * 4. 解析 diff hunk，更新行所有权映射
 * 5. 当新提交的作者与行前一所有者不同时，记录为 RepeatedChange
 */
public class CodeChurnDetector {

    private static final Logger log = LoggerFactory.getLogger(CodeChurnDetector.class);

    private static final String CLONE_DIR = System.getProperty("user.dir") + "/.git-clones";
    private static final int MAX_DIFF_SIZE = 100_000; // 单个文件 diff 超过此大小跳过
    private static final int MAX_CONTEXT_LINES = 10;  // AI 分类时携带的上下文字段行数

    private final ConfigEncryptor configEncryptor;
    private final DiffHunkParser hunkParser;

    public CodeChurnDetector(ConfigEncryptor configEncryptor) {
        this.configEncryptor = configEncryptor;
        this.hunkParser = new DiffHunkParser();
    }

    /**
     * 主入口：给定提交列表和仓库，检测其中的重复修改。
     *
     * @param commits  待分析的提交列表（将被按时间排序）
     * @param cloneDir 已克隆的仓库目录
     * @param sinceHash 起始 hash（用于解析 git log 范围）
     * @param untilHash 结束 hash
     * @return 检测到的所有重复修改
     */
    public List<RepeatedChange> detect(List<Commit> commits, File cloneDir,
                                        String sinceHash, String untilHash) {
        List<RepeatedChange> results = new ArrayList<>();
        if (commits == null || commits.isEmpty() || cloneDir == null || !cloneDir.exists()) {
            return results;
        }

        Git git = null;
        try {
            git = Git.open(cloneDir);

            ObjectId sinceId = git.getRepository().resolve(sinceHash + "^{commit}");
            ObjectId untilId = git.getRepository().resolve(untilHash + "^{commit}");
            if (sinceId == null || untilId == null) {
                log.warn("Cannot resolve hashes: since={}, until={}", sinceHash, untilHash);
                return results;
            }

            // Step 1: Walk commits in chronological order (oldest first)
            List<RevCommit> revCommits = new ArrayList<>();
            try (RevWalk revWalk = new RevWalk(git.getRepository())) {
                revWalk.markStart(revWalk.parseCommit(untilId));
                revWalk.markUninteresting(revWalk.parseCommit(sinceId));
                for (RevCommit rc : revWalk) {
                    if (rc.getParentCount() > 1) continue; // skip merge commits
                    revCommits.add(rc);
                }
            }
            // Reverse to get chronological order (oldest first)
            Collections.reverse(revCommits);
            log.info("Walking {} commits for churn detection (oldest first)", revCommits.size());

            // Build commit lookup for our model Commit objects
            Map<String, Commit> commitLookup = buildCommitLookup(commits);

            // Step 2: Process each commit — build line ownership map
            // filePath -> (lineNumber -> (authorName, changeRecord))
            Map<String, Map<Integer, LineOwnership>> ownershipMap = new LinkedHashMap<>();
            int processedCount = 0;

            for (RevCommit rc : revCommits) {
                processedCount++;
                if (rc.getParentCount() == 0) continue; // skip root commit

                String commitId = rc.getName();
                Commit modelCommit = commitLookup.get(commitId);
                String authorName = rc.getAuthorIdent().getName();
                String authorEmail = rc.getAuthorIdent().getEmailAddress();
                Date timestamp = new Date(rc.getAuthorIdent().getWhen().getTime());
                String message = rc.getShortMessage();

                // Get diffs between parent and this commit
                List<DiffEntry> diffs = getDiffs(git, rc);
                if (diffs.isEmpty()) continue;

                for (DiffEntry entry : diffs) {
                    String filePath = entry.getNewPath();
                    if (entry.getChangeType() == DiffEntry.ChangeType.DELETE) {
                        filePath = entry.getOldPath();
                    }

                    String diffText = getDiffText(git, entry);
                    if (diffText == null || diffText.length() > MAX_DIFF_SIZE) {
                        log.debug("Skipping large/binary diff for {}", filePath);
                        continue;
                    }

                    List<DiffHunk> hunks = hunkParser.parse(diffText, filePath);
                    for (DiffHunk hunk : hunks) {
                        int startLine = hunk.getNewLineStart();
                        int endLine = hunk.getNewLineEnd();

                        // Ensure entry in ownership map
                        Map<Integer, LineOwnership> fileOwnership =
                                ownershipMap.computeIfAbsent(filePath, k -> new TreeMap<>());

                        // Check for overlaps with different authors
                        String overlapAuthor = null;
                        ChangeRecord overlapRecord = null;
                        for (int line = startLine; line <= endLine; line++) {
                            LineOwnership existing = fileOwnership.get(line);
                            if (existing != null && !existing.authorName.equals(authorName)) {
                                overlapAuthor = existing.authorName;
                                overlapRecord = existing.lastChange;
                                break;
                            }
                        }

                        if (overlapAuthor != null && overlapRecord != null) {
                            // Found a repeated change!
                            RepeatedChange repeated = new RepeatedChange();
                            repeated.setFilePath(filePath);

                            ChangeRecord newRecord = buildChangeRecord(filePath, startLine, endLine,
                                    authorName, authorEmail, commitId, message, timestamp, hunk);
                            repeated.getRecords().add(overlapRecord);
                            repeated.getRecords().add(newRecord);

                            results.add(repeated);
                            log.debug("Repeated change detected: {} lines {}-{} by {} → {}",
                                    filePath, startLine, endLine, overlapAuthor, authorName);
                        }

                        // Update ownership for all lines in this hunk
                        ChangeRecord newRecord = buildChangeRecord(filePath, startLine, endLine,
                                authorName, authorEmail, commitId, message, timestamp, hunk);
                        LineOwnership ownership = new LineOwnership(authorName, newRecord);
                        for (int line = startLine; line <= endLine; line++) {
                            fileOwnership.put(line, ownership);
                        }
                    }
                }

                if (processedCount % 50 == 0) {
                    log.info("Churn detection progress: {}/{} commits, {} repeated changes found",
                            processedCount, revCommits.size(), results.size());
                }
            }

            log.info("Churn detection complete: {} commits processed, {} repeated changes detected",
                    processedCount, results.size());

        } catch (Exception e) {
            log.error("Churn detection failed: {}", e.getMessage(), e);
        } finally {
            if (git != null) git.close();
        }

        return results;
    }

    /**
     * 获取单个提交相对于父提交的所有文件 diff。
     */
    private List<DiffEntry> getDiffs(Git git, RevCommit commit) {
        try {
            RevCommit parent = commit.getParent(0);
            try (ObjectReader reader = git.getRepository().newObjectReader()) {
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, parent.getTree().getId());
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, commit.getTree().getId());
                return git.diff().setOldTree(oldTree).setNewTree(newTree).call();
            }
        } catch (Exception e) {
            log.debug("Failed to get diffs for commit {}: {}", commit.getName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取 diff entry 的 unified diff 文本。
     */
    private String getDiffText(Git git, DiffEntry entry) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(git.getRepository());
                formatter.setContext(5); // 5 lines of context
                formatter.format(entry);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            log.debug("Failed to format diff for {}: {}", entry.getNewPath(), e.getMessage());
            return null;
        }
    }

    /**
     * 从 DiffHunk 和 commit 信息构建 ChangeRecord。
     */
    private ChangeRecord buildChangeRecord(String filePath, int lineStart, int lineEnd,
                                            String authorName, String authorEmail,
                                            String commitId, String message,
                                            Date timestamp, DiffHunk hunk) {
        ChangeRecord record = new ChangeRecord();
        record.setFilePath(filePath);
        record.setLineStart(lineStart);
        record.setLineEnd(lineEnd);
        record.setAuthorName(authorName);
        record.setAuthorEmail(authorEmail);
        record.setCommitId(commitId);
        record.setCommitMessage(message);
        record.setTimestamp(timestamp);

        // Build a compact diff snippet for AI context
        StringBuilder snippet = new StringBuilder();
        if (hunk.getContextBefore() != null && !hunk.getContextBefore().isEmpty()) {
            snippet.append(hunk.getContextBefore()).append("\n");
        }
        if (hunk.getChangeContent() != null && !hunk.getChangeContent().isEmpty()) {
            snippet.append(hunk.getChangeContent()).append("\n");
        }
        if (hunk.getContextAfter() != null && !hunk.getContextAfter().isEmpty()) {
            snippet.append(hunk.getContextAfter());
        }
        record.setDiffSnippet(snippet.toString().trim());

        return record;
    }

    /**
     * 构建 Commit 对象查找表（按 hash）。
     */
    private Map<String, Commit> buildCommitLookup(List<Commit> commits) {
        Map<String, Commit> lookup = new HashMap<>();
        for (Commit c : commits) {
            if (c.getId() != null) {
                lookup.put(c.getId(), c);
            }
        }
        return lookup;
    }

    /**
     * 克隆或打开仓库目录（复用 GitCloneService 的模式）。
     */
    public File resolveCloneDir(ProjectConfig config) {
        String safeName = (config.getName() != null ? config.getName() : "repo")
                .replaceAll("[^a-zA-Z0-9_-]", "_");
        return new File(CLONE_DIR, safeName + "_" + (config.getId() != null ? config.getId() : "new"));
    }

    /**
     * 确保仓库已克隆且为最新（fetch）。
     */
    public File ensureCloned(ProjectConfig config) {
        File cloneDir = resolveCloneDir(config);
        try {
            if (cloneDir.exists()) {
                Git git = Git.open(cloneDir);
                git.fetch()
                        .setCredentialsProvider(createCredentials(config))
                        .call();
                git.close();
            } else {
                cloneDir.getParentFile().mkdirs();
                Git git = Git.cloneRepository()
                        .setURI(config.getGitlabUrl())
                        .setDirectory(cloneDir)
                        .setCredentialsProvider(createCredentials(config))
                        .setCloneAllBranches(true)
                        .call();
                git.close();
            }
        } catch (Exception e) {
            log.error("Failed to clone/fetch repo: {}", e.getMessage(), e);
        }
        return cloneDir;
    }

    private UsernamePasswordCredentialsProvider createCredentials(ProjectConfig config) {
        String decrypted = configEncryptor.decrypt(config.getCredentials());
        if (decrypted != null && decrypted.contains(":")) {
            String[] parts = decrypted.split(":", 2);
            return new UsernamePasswordCredentialsProvider(parts[0], parts[1]);
        }
        return new UsernamePasswordCredentialsProvider("oauth2", decrypted);
    }

    /**
     * 内部类：行所有权记录。
     */
    private static class LineOwnership {
        final String authorName;
        final ChangeRecord lastChange;

        LineOwnership(String authorName, ChangeRecord lastChange) {
            this.authorName = authorName;
            this.lastChange = lastChange;
        }
    }
}
