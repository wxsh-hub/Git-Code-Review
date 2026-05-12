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
