package com.devops.ai.core.review.collector;

import com.devops.ai.core.review.model.CodeReviewContext;
import com.devops.ai.core.review.model.FileDiff;
import com.devops.ai.core.model.Commit;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class CodeReviewDataCollector {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewDataCollector.class);

    private static final String CLONE_DIR = System.getProperty("user.dir") + "/.git-clones";
    private final ConfigEncryptor configEncryptor;

    public CodeReviewDataCollector(ConfigEncryptor configEncryptor) {
        this.configEncryptor = configEncryptor;
    }

    public List<FileDiff> collectDiffs(ProjectConfig config, String sinceHash, String untilHash) {
        List<FileDiff> diffs = new ArrayList<>();
        if (sinceHash == null || untilHash == null || sinceHash.isEmpty() || untilHash.isEmpty()) {
            log.warn("sinceHash or untilHash is empty, cannot collect diffs");
            return diffs;
        }

        File cloneDir = getCloneDir(config);
        Git git = null;
        try {
            if (cloneDir.exists()) {
                git = Git.open(cloneDir);
                git.fetch().setCredentialsProvider(createCredentials(config)).call();
            } else {
                cloneDir.getParentFile().mkdirs();
                git = Git.cloneRepository()
                        .setURI(config.getGitlabUrl())
                        .setDirectory(cloneDir)
                        .setCredentialsProvider(createCredentials(config))
                        .setCloneAllBranches(true)
                        .call();
            }

            ObjectId sinceId = git.getRepository().resolve(sinceHash + "^{commit}");
            ObjectId untilId = git.getRepository().resolve(untilHash + "^{commit}");
            if (sinceId == null || untilId == null) {
                log.warn("Cannot resolve sinceHash={} or untilHash={}", sinceHash, untilHash);
                return diffs;
            }

            try (RevWalk revWalk = new RevWalk(git.getRepository())) {
                RevCommit sinceCommit = revWalk.parseCommit(sinceId);
                RevCommit untilCommit = revWalk.parseCommit(untilId);

                ObjectReader reader = git.getRepository().newObjectReader();
                CanonicalTreeParser sinceTree = new CanonicalTreeParser();
                sinceTree.reset(reader, sinceCommit.getTree().getId());
                CanonicalTreeParser untilTree = new CanonicalTreeParser();
                untilTree.reset(reader, untilCommit.getTree().getId());

                List<DiffEntry> entries = git.diff()
                        .setOldTree(sinceTree)
                        .setNewTree(untilTree)
                        .call();

                for (DiffEntry entry : entries) {
                    FileDiff diff = new FileDiff();
                    diff.setFilePath(entry.getNewPath());
                    diff.setChangeType(entry.getChangeType().name());

                    // Get unified diff text
                    ByteArrayOutputStream diffOut = new ByteArrayOutputStream();
                    try (DiffFormatter formatter = new DiffFormatter(diffOut)) {
                        formatter.setRepository(git.getRepository());
                        formatter.format(entry);
                    }
                    String unifiedDiff = diffOut.toString(StandardCharsets.UTF_8.name());
                    diff.setUnifiedDiff(unifiedDiff);

                    // Get old and new content
                    if (entry.getOldId() != null) {
                        diff.setOldContent(getFileContent(git, entry.getOldId().toObjectId()));
                    }
                    if (entry.getNewId() != null) {
                        diff.setNewContent(getFileContent(git, entry.getNewId().toObjectId()));
                    }

                    diffs.add(diff);
                }
                log.info("Collected {} file diffs between {} and {}", diffs.size(), sinceHash, untilHash);
            }
        } catch (Exception e) {
            log.error("Failed to collect diffs: {}", e.getMessage(), e);
        } finally {
            if (git != null) git.close();
        }
        return diffs;
    }

    private String getFileContent(Git git, ObjectId objectId) {
        try {
            byte[] bytes = git.getRepository().open(objectId).getBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Failed to read object {}: {}", objectId.name(), e.getMessage());
            return null;
        }
    }

    private UsernamePasswordCredentialsProvider createCredentials(ProjectConfig config) {
        String decrypted = configEncryptor.decrypt(config.getCredentials());
        if (decrypted != null && decrypted.contains(":")) {
            String[] parts = decrypted.split(":", 2);
            return new UsernamePasswordCredentialsProvider(parts[0], parts[1]);
        }
        return new UsernamePasswordCredentialsProvider("oauth2", decrypted);
    }

    private File getCloneDir(ProjectConfig config) {
        String safeName = (config.getName() != null ? config.getName() : "repo")
                .replaceAll("[^a-zA-Z0-9_-]", "_");
        return new File(CLONE_DIR, safeName + "_" + (config.getId() != null ? config.getId() : "new"));
    }

    public File resolveCloneDir(ProjectConfig config) {
        return getCloneDir(config);
    }

    /**
     * 确保本地 clone 仓库切到正确的分支。
     *
     * @param cloneDir      clone 目录
     * @param requestBranch 请求中指定的分支
     * @param config        项目配置（用于创建认证凭据）
     */
    public void checkoutBranch(File cloneDir, String requestBranch, ProjectConfig config) {
        if (!cloneDir.exists()) return;

        try (Git git = Git.open(cloneDir)) {
            String currentBranch = git.getRepository().getBranch();
            log.info("Local clone on branch '{}', request branch='{}'", currentBranch, requestBranch);

            UsernamePasswordCredentialsProvider credentials = createCredentials(config);

            // 如果分支匹配 → 直接复用，只 pull 最新
            if (requestBranch != null && requestBranch.equals(currentBranch)) {
                log.info("Branch '{}' matches, reusing local clone", currentBranch);
                try {
                    git.pull().setCredentialsProvider(credentials).call();
                    log.info("Pulled latest on '{}'", currentBranch);
                } catch (Exception e) {
                    log.warn("Pull failed on '{}': {}, continuing with cached version", currentBranch, e.getMessage());
                }
                return;
            }

            // 分支不匹配 → 尝试切换到请求的分支
            if (requestBranch != null && !requestBranch.isEmpty()) {
                log.info("Switching clone from '{}' to '{}'", currentBranch, requestBranch);
                try {
                    // 先 fetch 所有远程分支
                    git.fetch().setCredentialsProvider(credentials).call();

                    // 检查远程是否有该分支
                    String remoteBranch = "origin/" + requestBranch;
                    boolean remoteExists = git.branchList()
                            .setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE)
                            .call().stream()
                            .anyMatch(ref -> ref.getName().equals("refs/remotes/" + remoteBranch));

                    if (remoteExists) {
                        // 检查本地是否已有该分支
                        boolean localExists = git.branchList().call().stream()
                                .anyMatch(ref -> ref.getName().equals("refs/heads/" + requestBranch));

                        if (localExists) {
                            git.checkout().setName(requestBranch).call();
                        } else {
                            git.checkout()
                                    .setCreateBranch(true)
                                    .setName(requestBranch)
                                    .setStartPoint(remoteBranch)
                                    .call();
                        }
                        git.pull().setCredentialsProvider(credentials).call();
                        log.info("Successfully switched to branch '{}'", requestBranch);
                        return;
                    } else {
                        log.warn("Remote branch '{}' not found, staying on '{}'", requestBranch, currentBranch);
                        return;
                    }
                } catch (Exception e) {
                    log.warn("Failed to switch to '{}': {}, staying on '{}'", requestBranch, e.getMessage(), currentBranch);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to open clone at {}: {}", cloneDir, e.getMessage());
        }
    }

    private void deleteRecursively(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        dir.delete();
    }

    private int countCommits(Git git) {
        try {
            int count = 0;
            for (RevCommit c : git.log().call()) {
                count++;
                if (count > 10000) break;
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean checkoutRef(Git git, String ref) {
        try {
            git.checkout().setName(ref).call();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getRootCommitHash(File cloneDir) {
        try (Git git = Git.open(cloneDir)) {
            ObjectId head = git.getRepository().resolve("HEAD");
            try (RevWalk revWalk = new RevWalk(git.getRepository())) {
                RevCommit headCommit = revWalk.parseCommit(head);
                revWalk.markStart(headCommit);
                RevCommit root = null;
                for (RevCommit commit : revWalk) {
                    root = commit;
                }
                return root != null ? root.getId().getName() : null;
            }
        } catch (Exception e) {
            log.warn("Failed to get root commit hash from {}: {}", cloneDir, e.getMessage());
            return null;
        }
    }
}
