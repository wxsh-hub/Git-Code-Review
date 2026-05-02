package com.devops.ai.core.gitlab;

import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuthManager {

    private static final Logger log = LoggerFactory.getLogger(AuthManager.class);

    private final ConfigEncryptor configEncryptor;
    private final GitCloneService gitCloneService;

    public AuthManager(ConfigEncryptor configEncryptor, GitCloneService gitCloneService) {
        this.configEncryptor = configEncryptor;
        this.gitCloneService = gitCloneService;
    }

    public GitLabApi createApi(ProjectConfig config) {
        String gitlabUrl = config.getGitlabUrl();
        String authType = config.getAuthType();
        String credentials = config.getCredentials();

        if (credentials != null && !credentials.isEmpty()) {
            try {
                credentials = configEncryptor.decrypt(credentials);
            } catch (Exception e) {
                log.warn("Failed to decrypt credentials, using raw value: {}", e.getMessage(), e);
            }
        }

        if ("password".equalsIgnoreCase(authType)) {
            throw new IllegalArgumentException(
                    "GitLab CE v18.x has disabled password-based API authentication.\n" +
                    "Solution: Use clone mode with username+password, or generate a Personal Access Token.");
        }

        if ("token".equalsIgnoreCase(authType)) {
            GitLabApi api = new GitLabApi(gitlabUrl, credentials);
            disableSslIfNeeded(api, gitlabUrl);
            return api;
        }

        throw new IllegalArgumentException("Unsupported auth type: " + authType);
    }

    private void disableSslIfNeeded(GitLabApi api, String gitlabUrl) {
        if (gitlabUrl != null && gitlabUrl.startsWith("https://")) {
            api.setIgnoreCertificateErrors(true);
            log.info("SSL certificate verification disabled for GitLab API: {}", gitlabUrl);
        }
    }

    public boolean testConnection(ProjectConfig config) {
        if ("clone".equals(config.getConnectMode())) {
            return gitCloneService.testConnection(config);
        }

        try {
            GitLabApi api = createApi(config);
            api.getProjectApi().getProjects(1, 1);
            return true;
        } catch (GitLabApiException e) {
            log.warn("GitLab connection test failed for {}: {}", config.getGitlabUrl(), e.getMessage(), e);
            throw new com.devops.ai.infrastructure.exception.GitLabApiException(
                    "GitLab connection failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error during GitLab connection test for {}: {}", config.getGitlabUrl(), e.getMessage(), e);
            throw new com.devops.ai.infrastructure.exception.GitLabApiException(
                    "Connection test error: " + e.getMessage(), e);
        }
    }
}
