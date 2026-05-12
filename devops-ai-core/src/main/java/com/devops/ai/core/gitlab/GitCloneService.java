package com.devops.ai.core.gitlab;

import com.devops.ai.core.model.AuthorInfo;
import com.devops.ai.core.model.Branch;
import com.devops.ai.core.model.Commit;
import com.devops.ai.core.model.ProjectInfo;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;
import org.eclipse.jgit.util.FS;

import javax.net.ssl.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class GitCloneService {

    private static final Logger log = LoggerFactory.getLogger(GitCloneService.class);

    private static final String CLONE_DIR = System.getProperty("user.dir") + "/.git-clones";

    private final ConfigEncryptor configEncryptor;

    private static final TrustManager[] TRUST_ALL = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }
    };

    private static final TransportConfigCallback INSECURE_SSL_CALLBACK = transport -> {
        if (transport instanceof TransportHttp) {
            HttpConnectionFactory delegate = new JDKHttpConnectionFactory();
            ((TransportHttp) transport).setHttpConnectionFactory(new HttpConnectionFactory() {
                @Override
                public HttpConnection create(java.net.URL url) throws java.io.IOException {
                    HttpConnection conn = delegate.create(url);
                    configureInsecure(conn);
                    return conn;
                }
                @Override
                public HttpConnection create(java.net.URL url, java.net.Proxy proxy) throws java.io.IOException {
                    HttpConnection conn = delegate.create(url, proxy);
                    configureInsecure(conn);
                    return conn;
                }
                private void configureInsecure(HttpConnection conn) {
                    try {
                        conn.configure(null, TRUST_ALL, new SecureRandom());
                        conn.setHostnameVerifier((hostname, session) -> true);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to configure insecure SSL", e);
                    }
                }
            });
        }
    };

    public GitCloneService(ConfigEncryptor configEncryptor) {
        this.configEncryptor = configEncryptor;
    }

    @PostConstruct
    public void fixPostBuffer() {
        try {
            FS fs = FS.detect();
            File configFile = new File(fs.userHome(), ".gitconfig");
            FileBasedConfig userConfig = new FileBasedConfig(configFile, fs);
            userConfig.load();

            String postBuffer = userConfig.getString("http", null, "postBuffer");
            if (postBuffer != null && !postBuffer.isEmpty()) {
                try {
                    long val = Long.parseLong(postBuffer);
                    if (val > Integer.MAX_VALUE) {
                        log.warn("http.postBuffer in ~/.gitconfig is {} ({}), exceeds Java int range. Setting to 500MB.", val, postBuffer);
                        userConfig.setInt("http", null, "postBuffer", 524288000);
                        userConfig.save();
                        log.info("http.postBuffer in ~/.gitconfig has been reset to 524288000 (500MB)");
                    }
                } catch (NumberFormatException e) {
                    log.warn("http.postBuffer in ~/.gitconfig is not a valid number: {}", postBuffer);
                }
            }
        } catch (Exception e) {
            log.warn("Could not check/fix http.postBuffer: {}", e.getMessage());
        }
    }

    public List<Commit> getCommits(ProjectConfig config, String branch, int maxCount) {
        return getCommits(config, branch, maxCount, null, null, null, null);
    }

    public List<Commit> getCommits(ProjectConfig config, String branch, int maxCount,
                                    String sinceHash, String untilHash) {
        return getCommits(config, branch, maxCount, sinceHash, untilHash, null, null);
    }

    public List<Commit> getCommits(ProjectConfig config, String branch, int maxCount,
                                    String sinceHash, String untilHash,
                                    Date since, Date until) {
        File cloneDir = getCloneDir(config);
        Git git = null;
        try {
            if (cloneDir.exists()) {
                git = Git.open(cloneDir);
                git.fetch().setCredentialsProvider(createCredentials(config)).setTransportConfigCallback(INSECURE_SSL_CALLBACK).call();
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

            ObjectId branchId = null;
            if (branch != null && !branch.isEmpty()) {
                branchId = git.getRepository().resolve("refs/heads/" + branch);
                if (branchId == null) {
                    branchId = git.getRepository().resolve("refs/remotes/origin/" + branch);
                }
            }

            List<Commit> result = new ArrayList<>();
            Iterable<RevCommit> commits;

            if (sinceHash != null && !sinceHash.isEmpty() && branchId != null) {
                ObjectId sinceId = git.getRepository().resolve(sinceHash);
                if (sinceId == null) {
                    log.warn("Since hash {} not found in repository, falling back to full fetch", sinceHash);
                    commits = git.log().add(branchId).setMaxCount(maxCount).call();
                } else {
                    if (untilHash != null && !untilHash.isEmpty()) {
                        ObjectId untilId = git.getRepository().resolve(untilHash);
                        if (untilId != null) {
                            commits = git.log().addRange(sinceId, untilId).call();
                        } else {
                            log.warn("Until hash {} not found, using HEAD", untilHash);
                            commits = git.log().addRange(sinceId, branchId).call();
                        }
                    } else {
                        commits = git.log().addRange(sinceId, branchId).call();
                    }
                }
            } else if (untilHash != null && !untilHash.isEmpty() && branchId != null) {
                ObjectId untilId = git.getRepository().resolve(untilHash);
                if (untilId != null) {
                    commits = git.log().add(untilId).setMaxCount(maxCount).call();
                } else {
                    log.warn("Until hash {} not found, falling back to full fetch", untilHash);
                    commits = git.log().add(branchId).setMaxCount(maxCount).call();
                }
            } else if (branchId != null) {
                commits = git.log().add(branchId).setMaxCount(maxCount).call();
            } else {
                commits = git.log().setMaxCount(maxCount).call();
            }

            for (RevCommit rc : commits) {
                if (rc.getParentCount() > 1) {
                    continue;
                }
                Date commitDate = new Date(rc.getAuthorIdent().getWhen().getTime());
                if (since != null && commitDate.before(since)) {
                    continue;
                }
                if (until != null && commitDate.after(until)) {
                    continue;
                }
                Commit c = new Commit();
                c.setId(rc.getName());
                c.setMessage(rc.getShortMessage());
                c.setAuthorName(rc.getAuthorIdent().getName());
                c.setAuthorEmail(rc.getAuthorIdent().getEmailAddress());
                c.setCreatedAt(commitDate);
                c.setParentIds(new ArrayList<>());
                for (RevCommit parent : rc.getParents()) {
                    c.getParentIds().add(parent.getName());
                }
                result.add(c);
            }
            return result;

        } catch (Exception e) {
            log.error("Failed to get commits via clone: {}", e.getMessage(), e);
            throw new RuntimeException("克隆仓库获取提交记录失败: " + e.getMessage(), e);
        } finally {
            if (git != null) {
                git.close();
            }
        }
    }

    public boolean testConnection(ProjectConfig config) {
        File cloneDir = getCloneDir(config);
        Git git = null;
        try {
            if (cloneDir.exists()) {
                deleteDir(cloneDir);
            }

            if (!cloneDir.getParentFile().exists()) {
                cloneDir.getParentFile().mkdirs();
            }

            git = Git.cloneRepository()
                    .setURI(config.getGitlabUrl())
                    .setDirectory(cloneDir)
                    .setCredentialsProvider(createCredentials(config))
                    .setTransportConfigCallback(INSECURE_SSL_CALLBACK)
                    .setCloneAllBranches(true)
                    .call();
            return true;
        } catch (Exception e) {
            log.warn("Clone test failed for {}: {}", config.getGitlabUrl(), e.getMessage(), e);
            throw new RuntimeException("克隆仓库失败: " + e.getMessage(), e);
        } finally {
            if (git != null) {
                git.close();
            }
            try {
                deleteDir(cloneDir);
            } catch (Exception e) {
                log.warn("Failed to clean up clone directory: {}", e.getMessage());
            }
        }
    }
// ... existing code ...


    public ProjectInfo getProjectInfo(ProjectConfig config) {
        String url = config.getGitlabUrl();
        String projectName = extractProjectName(url);
        ProjectInfo info = new ProjectInfo();
        info.setId(config.getId() != null ? String.valueOf(config.getId()) : UUID.randomUUID().toString());
        info.setName(projectName);
        info.setNameWithNamespace(projectName);
        info.setWebUrl(url);
        info.setDefaultBranch("master");
        return info;
    }

    public List<Branch> getBranches(ProjectConfig config) {
        File cloneDir = getCloneDir(config);
        Git git = null;
        try {
            if (!cloneDir.exists()) {
                cloneDir.getParentFile().mkdirs();
                git = Git.cloneRepository()
                        .setURI(config.getGitlabUrl())
                        .setDirectory(cloneDir)
                        .setCredentialsProvider(createCredentials(config))
                        .setTransportConfigCallback(INSECURE_SSL_CALLBACK)
                        .setCloneAllBranches(true)
                        .call();
            } else {
                git = Git.open(cloneDir);
                git.fetch().setCredentialsProvider(createCredentials(config)).setTransportConfigCallback(INSECURE_SSL_CALLBACK).call();
            }

            List<Branch> result = new ArrayList<>();
            List<Ref> refs = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call();
            for (Ref ref : refs) {
                String name = ref.getName();
                if (name.startsWith("refs/remotes/origin/")) {
                    Branch b = new Branch();
                    b.setName(name.substring("refs/remotes/origin/".length()));
                    b.setCommitId(ref.getObjectId().getName());
                    result.add(b);
                }
            }
            return result;

        } catch (Exception e) {
            log.error("Failed to get branches via clone: {}", e.getMessage(), e);
            throw new RuntimeException("获取分支列表失败: " + e.getMessage(), e);
        } finally {
            if (git != null) {
                git.close();
            }
        }
    }

    public List<AuthorInfo> getAuthors(ProjectConfig config) {
        File cloneDir = getCloneDir(config);
        Git git = null;
        try {
            if (!cloneDir.exists()) {
                cloneDir.getParentFile().mkdirs();
                git = Git.cloneRepository()
                        .setURI(config.getGitlabUrl())
                        .setDirectory(cloneDir)
                        .setCredentialsProvider(createCredentials(config))
                        .setTransportConfigCallback(INSECURE_SSL_CALLBACK)
                        .setCloneAllBranches(true)
                        .call();
            } else {
                git = Git.open(cloneDir);
                git.fetch().setCredentialsProvider(createCredentials(config)).setTransportConfigCallback(INSECURE_SSL_CALLBACK).call();
            }

            Set<String> seen = new HashSet<>();
            List<AuthorInfo> result = new ArrayList<>();
            List<Ref> refs = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call();
            for (Ref ref : refs) {
                String name = ref.getName();
                if (!name.startsWith("refs/remotes/origin/")) {
                    continue;
                }
                String branchName = name.substring("refs/remotes/origin/".length());
                try {
                    Iterable<RevCommit> commits = git.log()
                            .add(git.getRepository().resolve(name))
                            .setMaxCount(500).call();
                    for (RevCommit rc : commits) {
                        String authorName = rc.getAuthorIdent().getName();
                        String email = rc.getAuthorIdent().getEmailAddress();
                        String key = authorName + " <" + email + ">";
                        if (seen.add(key)) {
                            result.add(new AuthorInfo(authorName, email));
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to get commits for branch {}: {}", branchName, e.getMessage());
                }
            }

            result.sort(Comparator.comparing(AuthorInfo::getName));
            return result;

        } catch (Exception e) {
            log.error("Failed to get authors via clone: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get authors via clone: " + e.getMessage(), e);
        } finally {
            if (git != null) {
                git.close();
            }
        }
    }

    private UsernamePasswordCredentialsProvider createCredentials(ProjectConfig config) {
        String decryptedCredentials = configEncryptor.decrypt(config.getCredentials());

        if (decryptedCredentials != null && decryptedCredentials.contains(":")) {
            String[] parts = decryptedCredentials.split(":", 2);
            String username = parts[0] != null && !parts[0].isEmpty() ? parts[0] : "";
            String password = parts[1] != null ? parts[1] : "";
            return new UsernamePasswordCredentialsProvider(username, password);
        }

        return new UsernamePasswordCredentialsProvider("oauth2", decryptedCredentials);
    }

    private File getCloneDir(ProjectConfig config) {
        String safeName = (config.getName() != null ? config.getName() : "repo")
                .replaceAll("[^a-zA-Z0-9_-]", "_");
        return new File(CLONE_DIR, safeName + "_" + (config.getId() != null ? config.getId() : "new"));
    }

    private String extractProjectName(String url) {
        if (url == null || url.isEmpty()) {
            return "unknown";
        }
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0) {
            return url.substring(lastSlash + 1);
        }
        return url;
    }

    private void deleteDir(File dir) {
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        deleteDir(f);
                    } else {
                        f.delete();
                    }
                }
            }
            dir.delete();
        }
    }
}
