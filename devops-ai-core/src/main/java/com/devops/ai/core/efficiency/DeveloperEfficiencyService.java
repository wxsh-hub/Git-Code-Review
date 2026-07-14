package com.devops.ai.core.efficiency;

import com.devops.ai.core.efficiency.model.*;
import com.devops.ai.core.model.Category;
import com.devops.ai.core.model.Commit;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 编排开发者效率分析流水线（v2）：
 * 基于 AI 提交分类结果取 "Bug修复" 类 commit，通过 JGit blame 反向溯源 bug 引入者。
 *
 * <p>不再使用全量 churn 检测和 LLM FIX/ENHANCE 分类。</p>
 */
@Service
public class DeveloperEfficiencyService {

    private static final Logger log = LoggerFactory.getLogger(DeveloperEfficiencyService.class);

    private static final String CLONE_DIR = System.getProperty("user.dir") + "/.git-clones";

    private final ConfigEncryptor configEncryptor;
    private final EfficiencyReportGenerator reportGenerator;

    public DeveloperEfficiencyService(ConfigEncryptor configEncryptor,
                                       EfficiencyReportGenerator reportGenerator) {
        this.configEncryptor = configEncryptor;
        this.reportGenerator = reportGenerator;
    }

    /**
     * 完整分析入口（v2）：基于 AI 分类的 "Bug修复" commits + git blame 追溯。
     *
     * @param allCommits 所有 commit
     * @param categories AI 分类结果（包含 "Bug修复" category）
     * @param findings   代码审查发现的 Finding 列表（Phase 7 跨管线传递，可为 null）
     * @param config     项目配置
     * @return Markdown 格式的分析报告段落
     */
    public String analyzeAndGenerateReport(List<Commit> allCommits,
                                            List<Category> categories,
                                            java.util.List<com.devops.ai.core.review.model.Finding> findings,
                                            ProjectConfig config,
                                            String sinceHash,
                                            String untilHash) {
        EfficiencyAnalysisResult result = analyzeFixes(allCommits, categories, findings, config, sinceHash, untilHash);
        return reportGenerator.generate(result);
    }

    /**
     * 核心方法：只处理 "Bug修复" 分类的 commit，用 git blame 追溯 bug 引入者。
     */
    public EfficiencyAnalysisResult analyzeFixes(List<Commit> allCommits,
                                                  List<Category> categories,
                                                  java.util.List<com.devops.ai.core.review.model.Finding> findings,
                                                  ProjectConfig config,
                                                  String sinceHash,
                                                  String untilHash) {
        long startTime = System.currentTimeMillis();
        EfficiencyAnalysisResult result = new EfficiencyAnalysisResult();
        result.setProjectName(config.getName());
        result.setBranch(config.getDefaultBranch());
        result.setCommitRange(sinceHash + ".." + untilHash);

        // Extract "Bug修复" commits
        List<Commit> fixCommits = extractFixCommits(categories);
        if (fixCommits.isEmpty()) {
            log.warn("No 'Bug修复' commits found in AI classification results");
            result.setAnalysisTimeMs(System.currentTimeMillis() - startTime);
            result.setDeveloperEfficiencies(buildEmptyEfficiencies(allCommits));
            if (findings != null) result.setFindings(findings);
            return result;
        }
        log.info("Found {} 'Bug修复' commits from AI classification", fixCommits.size());
        result.setTotalFixCommits(fixCommits.size());

        // Ensure repo cloned
        File cloneDir = ensureCloned(config);

        // Trace each fix commit back to the bug introducer via git blame
        List<DeveloperEfficiency.BugDetail> allBugDetails = new ArrayList<>();
        try (Git git = Git.open(cloneDir)) {
            for (int i = 0; i < fixCommits.size(); i++) {
                Commit fixCommit = fixCommits.get(i);
                try {
                    List<DeveloperEfficiency.BugDetail> bugs = traceBugIntroducers(git, fixCommit);
                    allBugDetails.addAll(bugs);
                } catch (Exception e) {
                    log.warn("Failed to trace bug for fix commit {}: {}",
                            fixCommit.getId() != null ? fixCommit.getId().substring(0, 8) : "?", e.getMessage());
                }
                if ((i + 1) % 10 == 0 || (i + 1) == fixCommits.size()) {
                    log.info("Traced {}/{} fix commits, found {} bug details",
                            i + 1, fixCommits.size(), allBugDetails.size());
                }
            }
        } catch (Exception e) {
            log.error("Failed to open git repo for blame: {}", e.getMessage(), e);
        }

        result.setAllBugDetails(allBugDetails);

        // Phase 7: pass code review findings for unremediated vulnerability section
        if (findings != null) {
            result.setFindings(findings);
        }

        // Aggregate per-developer metrics
        Map<String, DeveloperEfficiency> devMap = aggregateFromBugs(allCommits, allBugDetails, fixCommits);
        List<DeveloperEfficiency> efficiencies = new ArrayList<>(devMap.values());
        efficiencies.sort((a, b) -> Double.compare(b.getBugsIntroduced(), a.getBugsIntroduced()));
        result.setDeveloperEfficiencies(efficiencies);

        result.setAnalysisTimeMs(System.currentTimeMillis() - startTime);
        log.info("Efficiency analysis (v2 blame) complete in {}ms: {} fix commits, {} bugs, {} developers",
                result.getAnalysisTimeMs(), fixCommits.size(), allBugDetails.size(), efficiencies.size());

        return result;
    }

    // ==================== Blame Logic ====================

    /**
     * 对一个 fix commit，通过 git blame 追溯被修改的每一行的原始作者。
     *
     * <p>算法：</p>
     * <ol>
     *   <li>获取 parent → fix 的 diff（只关注被修改/删除的行）</li>
     *   <li>在 parent 版本上对每个被修改的文件运行 git blame</li>
     *   <li>对每个 hunk 中 old 版本被改动/删除的行，查出 blame 作者和 commit</li>
     *   <li>按作者去重（同一个人多行合并），多人均摊 1/N 份额</li>
     * </ol>
     */
    List<DeveloperEfficiency.BugDetail> traceBugIntroducers(Git git, Commit fixCommit) throws Exception {
        List<DeveloperEfficiency.BugDetail> bugDetails = new ArrayList<>();

        String fixHash = fixCommit.getId();
        List<String> parentIds = fixCommit.getParentIds();

        // Skip merge commits and root commits (no parent)
        if (parentIds == null || parentIds.isEmpty() || parentIds.size() > 1) {
            log.warn("Fix commit {} skipped: parentIds={}", fixHash.substring(0, Math.min(8, fixHash.length())),
                    parentIds == null ? "null" : parentIds.size() + " parents");
            return bugDetails;
        }

        String parentHash = parentIds.get(0);
        ObjectId parentId = git.getRepository().resolve(parentHash + "^{commit}");
        ObjectId fixId = git.getRepository().resolve(fixHash + "^{commit}");
        if (parentId == null || fixId == null) {
            log.warn("Cannot resolve fix commit {} or parent {} in repo",
                    fixHash.substring(0, Math.min(8, fixHash.length())),
                    parentHash.substring(0, Math.min(8, parentHash.length())));
            return bugDetails;
        }

        String fixAuthor = fixCommit.getAuthorName();
        String fixMsg = fixCommit.getMessage();
        String fixDate = formatDate(fixCommit.getCreatedAt());

        log.info("Tracing fix commit {} by {}: {}", fixHash.substring(0, Math.min(8, fixHash.length())),
                fixAuthor, truncateMsg(fixMsg));

        try (RevWalk revWalk = new RevWalk(git.getRepository());
             ObjectReader reader = git.getRepository().newObjectReader()) {

            RevCommit parentCommit = revWalk.parseCommit(parentId);
            RevCommit fixRevCommit = revWalk.parseCommit(fixId);

            // Get diff between parent and fix
            CanonicalTreeParser parentTree = new CanonicalTreeParser();
            parentTree.reset(reader, parentCommit.getTree().getId());
            CanonicalTreeParser fixTreeParser = new CanonicalTreeParser();
            fixTreeParser.reset(reader, fixRevCommit.getTree().getId());

            List<DiffEntry> diffs = git.diff()
                    .setOldTree(parentTree)
                    .setNewTree(fixTreeParser)
                    .call();

            log.info("  Fix {} has {} diffs", fixHash.substring(0, Math.min(8, fixHash.length())), diffs.size());

            // Cross-file dedup map: key = bug-introducing commitId, merges blame results across files
            Map<String, CrossFileBlameInfo> crossFileMap = new LinkedHashMap<>();

            for (DiffEntry diff : diffs) {
                // Skip deleted/renamed/binary files
                if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) continue;

                String filePath = diff.getNewPath();
                if (filePath == null || filePath.isEmpty()) {
                    filePath = diff.getOldPath();
                }
                if (filePath == null) continue;

                // Get the diff text to parse hunks
                String diffText;
                try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    DiffFormatter df = new DiffFormatter(out);
                    df.setRepository(git.getRepository());
                    df.setContext(0);  // No context, only changed lines
                    df.format(diff);
                    diffText = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }

                // Parse hunks to get old line ranges (the lines that were changed in parent)
                List<int[]> oldLineRanges = parseHunkRanges(diffText);
                if (oldLineRanges.isEmpty()) {
                    log.info("  No hunk ranges parsed from diff for {} ({} bytes), likely new file — skipping blame", filePath, diffText.length());
                    continue;
                }

                // Run git blame on the parent version of this file
                BlameResult blame;
                try {
                    blame = git.blame()
                            .setFilePath(filePath)
                            .setStartCommit(parentId)
                            .call();
                } catch (Exception e) {
                    log.warn("Cannot blame {} at {}: {}", filePath, parentHash.substring(0, Math.min(8, parentHash.length())), e.getMessage());
                    continue;
                }

                if (blame == null) {
                    log.debug("Blame returned null for {} at {}", filePath, parentHash.substring(0, 8));
                    continue;
                }

                int totalLines = blame.getResultContents().size();
                log.info("  Blame {}: {} lines, {} hunk ranges", filePath, totalLines, oldLineRanges.size());

                // Collect blame results for each changed line (per-file)
                // Map: blameAuthor -> {commitId, lineCount}
                Map<String, BlameLineInfo> authorMap = new LinkedHashMap<>();
                int otherFixLines = 0;
                int outOfRange = 0;
                for (int[] range : oldLineRanges) {
                    for (int line = range[0]; line <= range[1]; line++) {
                        // JGit blame is 0-indexed for lines, but diff hunks use 1-indexed
                        int blameLineIdx = line - 1;
                        if (blameLineIdx < 0 || blameLineIdx >= totalLines) {
                            outOfRange++;
                            continue;
                        }

                        try {
                            PersonIdent sourceAuthor = blame.getSourceAuthor(blameLineIdx);
                            RevCommit sourceCommit = blame.getSourceCommit(blameLineIdx);
                            if (sourceAuthor != null && sourceCommit != null) {
                                String blameAuthor = sourceAuthor.getName();
                                String blameCommitId = sourceCommit.getName();
                                if (blameAuthor != null) {
                                    otherFixLines++;
                                    authorMap.computeIfAbsent(blameAuthor,
                                            k -> new BlameLineInfo(blameCommitId,
                                                    sourceCommit.getShortMessage(),
                                                    sourceAuthor.getName(),
                                                    sourceAuthor.getWhen()))
                                            .incrementLine();
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Blame line {} in {} failed: {}", line, filePath, e.getMessage());
                        }
                    }
                }

                if (!authorMap.isEmpty()) {
                    log.info("Blame found {} different author(s) for fix {} -> {} (other-fix: {} lines, oob: {})",
                            authorMap.size(),
                            fixHash.substring(0, Math.min(8, fixHash.length())),
                            filePath,
                            otherFixLines, outOfRange);
                }

                // Merge into cross-file blame map (keyed by "commitId|authorName")
                // so the same commit by different authors is tracked separately for fair share splitting
                for (BlameLineInfo info : authorMap.values()) {
                    String dateStr = info.createdAt != null ? formatDate(info.createdAt) : "";
                    String crossKey = info.commitId + "|" + info.authorName;
                    CrossFileBlameInfo cross = crossFileMap.computeIfAbsent(crossKey,
                            k -> new CrossFileBlameInfo(info.commitId, info.commitMessage, info.authorName, dateStr));
                    cross.addFile(filePath, info.lineCount);
                }
            }

            // Group by commitId to calculate shares:
            // Each distinct commitId = one bug; if N authors contributed, each gets 1/N share
            if (!crossFileMap.isEmpty()) {
                Map<String, List<CrossFileBlameInfo>> byCommit = new LinkedHashMap<>();
                for (CrossFileBlameInfo cross : crossFileMap.values()) {
                    byCommit.computeIfAbsent(cross.commitId, k -> new ArrayList<>()).add(cross);
                }
                for (List<CrossFileBlameInfo> group : byCommit.values()) {
                    double sharePerAuthor = 1.0 / group.size();
                    for (CrossFileBlameInfo cross : group) {
                        DeveloperEfficiency.BugDetail detail = new DeveloperEfficiency.BugDetail();
                        detail.setCommitId(cross.commitId);
                        detail.setCommitMessage(cross.commitMessage);
                        detail.setIntroducedBy(cross.authorName);
                        detail.setCreatedAt(cross.createdAt != null ? cross.createdAt : "");
                        detail.setFilePath(String.join(", ", cross.filePaths));
                        detail.setLineCount(cross.totalLines);
                        detail.setShare(sharePerAuthor);
                        detail.setFixedBy(fixAuthor);
                        detail.setFixedCommitId(fixHash);
                        detail.setFixedMessage(fixMsg);
                        detail.setFixedAt(fixDate);
                        bugDetails.add(detail);
                    }
                }
            }
        }
        return bugDetails;
    }

    /**
     * Parse unified diff text to extract old-line ranges (the lines changed/deleted).
     * Returns list of [start, end] 1-indexed inclusive pairs.
     */
    List<int[]> parseHunkRanges(String diffText) {
        List<int[]> ranges = new ArrayList<>();
        if (diffText == null || diffText.isEmpty()) return ranges;

        String[] lines = diffText.split("\n");
        for (String line : lines) {
            // Match hunk header: @@ -oldStart[,oldCount] +newStart[,newCount] @@
            if (line.startsWith("@@")) {
                int endIdx = line.indexOf("@@", 2);
                if (endIdx < 0) continue;
                String headerPart = line.substring(2, endIdx).trim();
                String[] parts = headerPart.split("\\s+");
                if (parts.length < 1) continue;

                // Parse old range: -oldStart[,oldCount]
                String oldPart = parts[0];
                if (!oldPart.startsWith("-")) continue;
                oldPart = oldPart.substring(1);
                int comma = oldPart.indexOf(',');
                int oldStart, oldCount;
                if (comma > 0) {
                    oldStart = Integer.parseInt(oldPart.substring(0, comma));
                    oldCount = Integer.parseInt(oldPart.substring(comma + 1));
                } else {
                    oldStart = Integer.parseInt(oldPart);
                    oldCount = 1;
                }
                if (oldCount > 0) {
                    ranges.add(new int[]{oldStart, oldStart + oldCount - 1});
                }
            }
        }
        return ranges;
    }

    /**
     * Internal helper to track blame info per author.
     */
    private static class BlameLineInfo {
        final String commitId;
        final String commitMessage;
        final String authorName;
        final Date createdAt;
        int lineCount;

        BlameLineInfo(String commitId, String commitMessage, String authorName, Date createdAt) {
            this.commitId = commitId;
            this.commitMessage = commitMessage;
            this.authorName = authorName;
            this.createdAt = createdAt;
            this.lineCount = 0;
        }

        void incrementLine() { lineCount++; }
    }

    /**
     * Internal helper to deduplicate blame info across files within a single fix commit.
     * Multiple files blaming to the same bug-introducing commit are merged into one entry.
     */
    private static class CrossFileBlameInfo {
        final String commitId;
        final String commitMessage;
        final String authorName;
        String createdAt;
        final List<String> filePaths = new ArrayList<>();
        int totalLines = 0;

        CrossFileBlameInfo(String commitId, String commitMessage, String authorName, String createdAt) {
            this.commitId = commitId;
            this.commitMessage = commitMessage;
            this.authorName = authorName;
            this.createdAt = createdAt;
        }

        void addFile(String path, int lines) {
            if (!filePaths.contains(path)) {
                filePaths.add(path);
            }
            totalLines += lines;
        }
    }

    // ==================== Aggregation ====================

    /**
     * Aggregate per-developer stats from bug details and fix commits.
     */
    Map<String, DeveloperEfficiency> aggregateFromBugs(List<Commit> allCommits,
                                                        List<DeveloperEfficiency.BugDetail> allBugDetails,
                                                        List<Commit> fixCommits) {
        Map<String, DeveloperEfficiency> devMap = new LinkedHashMap<>();

        // Phase 1: Count total commits per author
        for (Commit commit : allCommits) {
            String name = commit.getAuthorName() != null ? commit.getAuthorName() : "未知";
            DeveloperEfficiency dev = devMap.computeIfAbsent(name, k -> {
                DeveloperEfficiency d = new DeveloperEfficiency();
                d.setAuthorName(k);
                d.setAuthorEmail(commit.getAuthorEmail());
                return d;
            });
            dev.setTotalCommits(dev.getTotalCommits() + 1);
        }

        // Phase 2: Count bugs introduced (from blame) and attach detail.
        // BugDetails are NOT deduplicated across fix commits — the same bug-introducing
        // commit fixed by different fix commits counts as multiple bugs.
        // FixDetails ARE deduplicated by fixCommitId: one fix commit = one fix action,
        // even if it fixes multiple bug-introducing commits.
        Map<String, DeveloperEfficiency.FixDetail> fixDedupMap = new LinkedHashMap<>();

        for (DeveloperEfficiency.BugDetail bug : allBugDetails) {
            // Bug introducer - use the blame author name from the BugDetail
            String introducerName = bug.getIntroducedBy() != null ? bug.getIntroducedBy() : "未知";

            DeveloperEfficiency dev = devMap.computeIfAbsent(introducerName, k -> {
                DeveloperEfficiency d = new DeveloperEfficiency();
                d.setAuthorName(k);
                return d;
            });
            dev.setBugsIntroduced(dev.getBugsIntroduced() + bug.getShare());
            dev.getBugDetails().add(bug);

            // Phase 7: count CONFIRMED vs false positive
            if (bug.isConfirmed()) {
                dev.setConfirmedCount(dev.getConfirmedCount() + 1);
            } else if ("FALSE_POSITIVE".equals(bug.getAttributionStatus())) {
                dev.setFalsePositiveCount(dev.getFalsePositiveCount() + 1);
            } else {
                // attributionStatus not yet set (Phase 6 not applied to bug details)
                // 默认视为已确认
                dev.setConfirmedCount(dev.getConfirmedCount() + 1);
            }

            // Fix for fixer — deduplicate by fixCommitId only:
            // one fix commit = one fix action, even if it fixes multiple bug-introducing commits.
            String fixerName = bug.getFixedBy() != null ? bug.getFixedBy() : "未知";
            String fixCommitId = bug.getFixedCommitId();
            if (fixCommitId == null || fixCommitId.isEmpty()) continue;

            DeveloperEfficiency.FixDetail existingFix = fixDedupMap.get(fixCommitId);
            if (existingFix == null) {
                DeveloperEfficiency.FixDetail fixDetail = new DeveloperEfficiency.FixDetail();
                fixDetail.setCommitId(fixCommitId);
                fixDetail.setCommitMessage(bug.getFixedMessage());
                fixDetail.setCreatedAt(bug.getFixedAt());
                fixDetail.setIntroducedBy(introducerName);
                fixDetail.setIntroducedByCommitId(bug.getCommitId());
                fixDetail.setIntroducedByMessage(bug.getCommitMessage());
                fixDetail.setFilePath(bug.getFilePath());
                fixDedupMap.put(fixCommitId, fixDetail);

                DeveloperEfficiency fixerDev = devMap.computeIfAbsent(fixerName, k -> {
                    DeveloperEfficiency d = new DeveloperEfficiency();
                    d.setAuthorName(k);
                    return d;
                });
                fixerDev.getFixDetails().add(fixDetail);
                fixerDev.setFixesMade(fixerDev.getFixesMade() + 1);
            } else {
                // Same fix commit, different bug-introducing commit — merge introducedBy info,
                // avoiding duplicate names/commitIds in the semicolon-separated lists
                if (!containsPart(existingFix.getIntroducedBy(), introducerName)) {
                    existingFix.setIntroducedBy(existingFix.getIntroducedBy() + "; " + introducerName);
                }
                if (!containsPart(existingFix.getIntroducedByCommitId(), bug.getCommitId())) {
                    existingFix.setIntroducedByCommitId(existingFix.getIntroducedByCommitId() + "; " + bug.getCommitId());
                }
                if (!containsPart(existingFix.getIntroducedByMessage(), bug.getCommitMessage())) {
                    existingFix.setIntroducedByMessage(existingFix.getIntroducedByMessage() + "; " + bug.getCommitMessage());
                }
                // Append file paths
                String existingPath = existingFix.getFilePath();
                if (existingPath != null && !existingPath.contains(bug.getFilePath())) {
                    existingFix.setFilePath(existingPath + ", " + bug.getFilePath());
                }
            }
        }

        // Phase 3: Calculate bug rate
        for (DeveloperEfficiency dev : devMap.values()) {
            int totalCommits = dev.getTotalCommits();
            double bugsIntroduced = dev.getBugsIntroduced();
            dev.setBugRate(totalCommits > 0 ? bugsIntroduced / totalCommits : 0);
        }

        return devMap;
    }

    private List<DeveloperEfficiency> buildEmptyEfficiencies(List<Commit> allCommits) {
        Map<String, DeveloperEfficiency> devMap = new LinkedHashMap<>();
        for (Commit commit : allCommits) {
            String name = commit.getAuthorName() != null ? commit.getAuthorName() : "未知";
            DeveloperEfficiency dev = devMap.computeIfAbsent(name, k -> {
                DeveloperEfficiency d = new DeveloperEfficiency();
                d.setAuthorName(k);
                return d;
            });
            dev.setTotalCommits(dev.getTotalCommits() + 1);
        }
        return new ArrayList<>(devMap.values());
    }

    // ==================== Git Clone/Fetch ====================

    /**
     * Ensure the project repository is cloned locally (same pattern as CodeChurnDetector).
     */
    File ensureCloned(ProjectConfig config) {
        File cloneDir = resolveCloneDir(config);
        Git git = null;
        try {
            if (cloneDir.exists()) {
                git = Git.open(cloneDir);
                git.fetch()
                        .setCredentialsProvider(createCredentials(config))
                        .setTransportConfigCallback(INSECURE_SSL_CALLBACK)
                        .call();
            } else {
                cloneDir.getParentFile().mkdirs();
                git = Git.cloneRepository()
                        .setURI(config.getGitlabUrl())
                        .setDirectory(cloneDir)
                        .setCredentialsProvider(createCredentials(config))
                        .setTransportConfigCallback(INSECURE_SSL_CALLBACK)
                        .setCloneAllBranches(true)
                        .call();
            }
        } catch (Exception e) {
            log.warn("Clone/fetch failed: {}", e.getMessage());
        } finally {
            if (git != null) git.close();
        }
        return cloneDir;
    }

    File resolveCloneDir(ProjectConfig config) {
        String safeName = config.getName() != null
                ? config.getName().replaceAll("[^a-zA-Z0-9_-]", "_")
                : "repo";
        return new File(CLONE_DIR, safeName + "_" + config.getId());
    }

    private UsernamePasswordCredentialsProvider createCredentials(ProjectConfig config) {
        try {
            String decrypted = configEncryptor.decrypt(config.getCredentials());
            int idx = decrypted.indexOf(':');
            if (idx > 0) {
                return new UsernamePasswordCredentialsProvider(
                        decrypted.substring(0, idx), decrypted.substring(idx + 1));
            }
            return new UsernamePasswordCredentialsProvider("oauth2", decrypted);
        } catch (Exception e) {
            return new UsernamePasswordCredentialsProvider("", "");
        }
    }

    // ==================== Utilities ====================

    /**
     * Extract "Bug修复" commits from the AI classifier's category results.
     */
    private List<Commit> extractFixCommits(List<Category> categories) {
        List<Commit> fixCommits = new ArrayList<>();
        if (categories == null) return fixCommits;
        for (Category cat : categories) {
            if ("Bug修复".equals(cat.getName()) && cat.getCommits() != null) {
                fixCommits.addAll(cat.getCommits());
            }
        }
        // Sort by time (oldest first) for blame to work correctly
        fixCommits.sort(Comparator.comparing(Commit::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return fixCommits;
    }

    private String formatDate(Date date) {
        if (date == null) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }

    private String truncateMsg(String msg) {
        if (msg == null) return "";
        String trimmed = msg.replace('\n', ' ').trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
    }

    /**
     * Check if a value already exists in a semicolon-separated list.
     */
    private boolean containsPart(String list, String value) {
        if (list == null || value == null) return false;
        for (String part : list.split("; ")) {
            if (part.trim().equals(value.trim())) return true;
        }
        return false;
    }

    // ==================== SSL Bypass ====================

    static final TransportConfigCallback INSECURE_SSL_CALLBACK = transport -> {
        if (transport instanceof TransportHttp) {
            TransportHttp httpTransport = (TransportHttp) transport;
            httpTransport.setHttpConnectionFactory(new HttpConnectionFactory() {
                @Override
                public HttpConnection create(URL url) throws java.io.IOException {
                    return new JDKHttpConnectionFactory() {
                        @Override
                        public HttpConnection create(URL url) throws java.io.IOException {
                            HttpConnection conn = super.create(url);
                            if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                                // SSL is already disabled globally in DevOpsAiApplication
                            }
                            return conn;
                        }
                    }.create(url);
                }

                @Override
                public HttpConnection create(URL url, java.net.Proxy proxy) throws java.io.IOException {
                    return create(url);
                }
            });
        }
    };
}
