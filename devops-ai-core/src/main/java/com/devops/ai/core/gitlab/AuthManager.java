package com.devops.ai.core.gitlab;

import com.devops.ai.infrastructure.entity.GitLabConfig;
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

    public GitLabApi createApi(GitLabConfig config) {
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
                    "GitLab CE v18.x 已禁用基于密码的 API 认证。\n" +
                    "原因：自 GitLab 13.0+ 起，密码认证在 REST API 中默认关闭。\n" +
                    "解决方案：\n" +
                    "  1. 推荐：在配置中选择「克隆模式」，使用用户名+密码通过 Git 协议连接\n" +
                    "  2. 或者在 GitLab 管理后台生成 Personal Access Token，使用「API 模式 + Token 认证」");
        }

        if ("token".equalsIgnoreCase(authType)) {
            GitLabApi api = new GitLabApi(gitlabUrl, credentials);
            disableSslIfNeeded(api, gitlabUrl);
            return api;
        }

        throw new IllegalArgumentException("不支持的认证类型: " + authType);
    }

    private void disableSslIfNeeded(GitLabApi api, String gitlabUrl) {
        if (gitlabUrl != null && gitlabUrl.startsWith("https://")) {
            api.setIgnoreCertificateErrors(true);
            log.info("SSL certificate verification disabled for GitLab API: {}", gitlabUrl);
        }
    }

    public boolean testConnection(GitLabConfig config) {
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
                    "GitLab 连接失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error during GitLab connection test for {}: {}", config.getGitlabUrl(), e.getMessage(), e);
            throw new com.devops.ai.infrastructure.exception.GitLabApiException(
                    "连接测试异常: " + e.getMessage(), e);
        }
    }
}
