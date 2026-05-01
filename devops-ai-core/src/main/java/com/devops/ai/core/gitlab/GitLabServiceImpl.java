package com.devops.ai.core.gitlab;

import com.devops.ai.core.model.AuthorInfo;
import com.devops.ai.core.model.Branch;
import com.devops.ai.core.model.Commit;
import com.devops.ai.core.model.GitLabCommitQuery;
import com.devops.ai.core.model.ProjectInfo;
import com.devops.ai.infrastructure.cache.CacheManager;
import com.devops.ai.infrastructure.entity.GitLabConfig;
import com.devops.ai.infrastructure.exception.GitLabApiException;
import com.devops.ai.infrastructure.repository.GitLabConfigRepository;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.Pager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import cn.hutool.core.util.StrUtil;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GitLabServiceImpl implements GitLabService {

    private static final Logger log = LoggerFactory.getLogger(GitLabServiceImpl.class);

    private final AuthManager authManager;
    private final GitLabConfigRepository configRepository;
    private final CacheManager cacheManager;
    private final GitCloneService gitCloneService;

    public GitLabServiceImpl(AuthManager authManager,
                             GitLabConfigRepository configRepository,
                             CacheManager cacheManager,
                             GitCloneService gitCloneService) {
        this.authManager = authManager;
        this.configRepository = configRepository;
        this.cacheManager = cacheManager;
        this.gitCloneService = gitCloneService;
    }

    private GitLabConfig getActiveConfig() {
        List<GitLabConfig> activeConfigs = configRepository.findByActiveTrue();
        if (activeConfigs.isEmpty()) {
            throw new GitLabApiException("No active GitLab configuration found");
        }
        return activeConfigs.get(0);
    }

    private GitLabApi getActiveApi() {
        GitLabConfig config = getActiveConfig();
        if ("clone".equals(config.getConnectMode())) {
            throw new GitLabApiException("Active config is in clone mode, API mode not available");
        }
        return authManager.createApi(config);
    }

    private boolean isCloneMode() {
        GitLabConfig config = getActiveConfig();
        return "clone".equals(config.getConnectMode());
    }

    @Override
    public List<Commit> getCommits(GitLabCommitQuery query) {
        List<Commit> result;

        if (isCloneMode()) {
            GitLabConfig config = getActiveConfig();
            String branch = query.getBranch();
            if (branch == null || branch.isEmpty()) {
                branch = "master";
            }

            String sinceHash = query.getSinceHash();
            String untilHash = query.getUntilHash();
            boolean hasHashRange = StrUtil.isNotBlank(sinceHash) || StrUtil.isNotBlank(untilHash);

            Date since = query.getSince();
            Date until = query.getUntil();
            boolean hasTimeRange = since != null || until != null;

            int maxCount = query.getPerPage() > 0 ? query.getPerPage() : 100;
            if (hasHashRange || hasTimeRange) {
                maxCount = Integer.MAX_VALUE;
            }

            result = gitCloneService.getCommits(config, branch, maxCount, sinceHash, untilHash, since, until);
        } else {
            try {
                GitLabApi api = getActiveApi();
                String projectId = query.getProjectId();

                List<org.gitlab4j.api.models.Commit> gitlabCommits;

                if (!StrUtil.isBlank(query.getSinceHash())) {
                    gitlabCommits = api.getCommitsApi().getCommits(projectId, query.getSinceHash(), "HEAD");
                    if (!StrUtil.isBlank(query.getUntilHash())) {
                        List<org.gitlab4j.api.models.Commit> trimmed = new ArrayList<>();
                        boolean foundUntil = false;
                        for (org.gitlab4j.api.models.Commit c : gitlabCommits) {
                            if (c.getId().equals(query.getSinceHash())) {
                                break;
                            }
                            if (foundUntil || c.getId().equals(query.getUntilHash())) {
                                foundUntil = true;
                                trimmed.add(c);
                            }
                        }
                        gitlabCommits = trimmed;
                    }
                } else {
                    String branch = query.getBranch();
                    Date since = query.getSince();
                    Date until = query.getUntil();

                    if (branch != null && !branch.isEmpty() && since != null && until != null) {
                        Pager<org.gitlab4j.api.models.Commit> pager = api.getCommitsApi().getCommits(
                                projectId, branch, since, until, query.getPerPage());
                        gitlabCommits = new ArrayList<>();
                        while (pager.hasNext()) {
                            gitlabCommits.addAll(pager.next());
                        }
                    } else if (since != null && until != null) {
                        Pager<org.gitlab4j.api.models.Commit> pager = api.getCommitsApi().getCommits(
                                projectId, null, since, until, query.getPerPage());
                        gitlabCommits = new ArrayList<>();
                        while (pager.hasNext()) {
                            gitlabCommits.addAll(pager.next());
                        }
                    } else {
                        gitlabCommits = api.getCommitsApi().getCommits(
                                projectId, query.getPerPage(), query.getPage());
                    }
                }

                result = gitlabCommits.stream()
                        .map(this::convertToModel)
                        .filter(c -> !c.getMessage().startsWith("Merge"))
                        .collect(Collectors.toList());

            } catch (org.gitlab4j.api.GitLabApiException e) {
                log.error("Failed to fetch commits: {}", e.getMessage(), e);
                throw new GitLabApiException("Failed to fetch commits from GitLab: " + e.getMessage(), e);
            }
        }

        if (query.getAuthor() != null && !query.getAuthor().isEmpty()) {
            result = result.stream()
                    .filter(c -> c.getAuthorName() != null && c.getAuthorName().equals(query.getAuthor()))
                    .collect(Collectors.toList());
        }

        log.info("Fetched {} commits from project {}", result.size(), query.getProjectId());
        return result;
    }

    @Override
    public ProjectInfo getProjectInfo(String projectId) {
        if (isCloneMode()) {
            return gitCloneService.getProjectInfo(getActiveConfig());
        }

        String cacheKey = "project_" + projectId;
        ProjectInfo cached = (ProjectInfo) cacheManager.getProject(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            GitLabApi api = getActiveApi();
            org.gitlab4j.api.models.Project gitlabProject = api.getProjectApi().getProject(projectId);
            ProjectInfo info = convertToProjectInfo(gitlabProject);

            cacheManager.putProject(cacheKey, info);
            return info;

        } catch (org.gitlab4j.api.GitLabApiException e) {
            log.error("Failed to fetch project info: {}", e.getMessage(), e);
            throw new GitLabApiException("Failed to fetch project info from GitLab: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Branch> getBranches(String projectId) {
        if (isCloneMode()) {
            return gitCloneService.getBranches(getActiveConfig());
        }

        String cacheKey = "branches_" + projectId;
        @SuppressWarnings("unchecked")
        List<Branch> cached = (List<Branch>) cacheManager.getBranch(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            GitLabApi api = getActiveApi();
            List<org.gitlab4j.api.models.Branch> gitlabBranches = api.getRepositoryApi().getBranches(projectId);

            List<Branch> result = gitlabBranches.stream()
                    .map(this::convertToBranch)
                    .collect(Collectors.toList());

            cacheManager.putBranch(cacheKey, result);
            return result;

        } catch (org.gitlab4j.api.GitLabApiException e) {
            log.error("Failed to fetch branches: {}", e.getMessage(), e);
            throw new GitLabApiException("Failed to fetch branches from GitLab: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean testConnection(GitLabConfig config) {
        return authManager.testConnection(config);
    }

    @Override
    public List<ProjectInfo> getProjects() {
        if (isCloneMode()) {
            return Collections.singletonList(gitCloneService.getProjectInfo(getActiveConfig()));
        }

        try {
            GitLabApi api = getActiveApi();
            List<org.gitlab4j.api.models.Project> projects = api.getProjectApi().getProjects();

            return projects.stream()
                    .map(this::convertToProjectInfo)
                    .collect(Collectors.toList());

        } catch (org.gitlab4j.api.GitLabApiException e) {
            log.error("Failed to fetch projects: {}", e.getMessage(), e);
            throw new GitLabApiException("Failed to fetch projects from GitLab: " + e.getMessage(), e);
        }
    }

    @Override
    public List<AuthorInfo> getAuthors(String projectId) {
        if (isCloneMode()) {
            GitLabConfig config = getActiveConfig();
            return gitCloneService.getAuthors(config);
        }

        try {
            GitLabApi api = getActiveApi();
            List<org.gitlab4j.api.models.Commit> commits = api.getCommitsApi().getCommits(projectId, 100, 1);

            Set<String> seen = new HashSet<>();
            List<AuthorInfo> result = new ArrayList<>();
            for (org.gitlab4j.api.models.Commit c : commits) {
                String name = c.getAuthorName();
                String email = c.getAuthorEmail();
                if (name == null) continue;
                String key = name + " <" + (email != null ? email : "") + ">";
                if (seen.add(key)) {
                    result.add(new AuthorInfo(name, email));
                }
            }

            result.sort(Comparator.comparing(AuthorInfo::getName));
            return result;

        } catch (org.gitlab4j.api.GitLabApiException e) {
            log.error("Failed to fetch authors: {}", e.getMessage(), e);
            throw new GitLabApiException("Failed to fetch authors from GitLab: " + e.getMessage(), e);
        }
    }

    private Commit convertToModel(org.gitlab4j.api.models.Commit gitlabCommit) {
        Commit commit = new Commit();
        commit.setId(gitlabCommit.getId());
        String msg = gitlabCommit.getMessage();
        if (msg != null) {
            int idx = msg.indexOf('\n');
            msg = idx > 0 ? msg.substring(0, idx).trim() : msg.trim();
        }
        commit.setMessage(msg);
        commit.setAuthorName(gitlabCommit.getAuthorName());
        commit.setAuthorEmail(gitlabCommit.getAuthorEmail());
        commit.setCreatedAt(gitlabCommit.getCreatedAt());
        commit.setParentIds(gitlabCommit.getParentIds());
        return commit;
    }

    private ProjectInfo convertToProjectInfo(org.gitlab4j.api.models.Project gitlabProject) {
        ProjectInfo info = new ProjectInfo();
        info.setId(String.valueOf(gitlabProject.getId()));
        info.setName(gitlabProject.getName());
        info.setNameWithNamespace(gitlabProject.getNameWithNamespace());
        info.setDescription(gitlabProject.getDescription());
        info.setWebUrl(gitlabProject.getWebUrl());
        info.setDefaultBranch(gitlabProject.getDefaultBranch());
        info.setVisibility(gitlabProject.getVisibility() != null ? gitlabProject.getVisibility().toString() : null);
        return info;
    }

    private Branch convertToBranch(org.gitlab4j.api.models.Branch gitlabBranch) {
        Branch branch = new Branch();
        branch.setName(gitlabBranch.getName());
        if (gitlabBranch.getCommit() != null) {
            branch.setCommitId(gitlabBranch.getCommit().getId());
        }
        Boolean merged = gitlabBranch.getMerged();
        branch.setMerged(merged != null && merged);
        return branch;
    }
}
