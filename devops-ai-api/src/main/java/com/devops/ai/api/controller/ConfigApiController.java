package com.devops.ai.api.controller;

import com.devops.ai.api.dto.ApiResponse;
import com.devops.ai.core.gitlab.GitLabService;
import com.devops.ai.infrastructure.entity.AiConfig;
import com.devops.ai.infrastructure.entity.GitLabConfig;
import com.devops.ai.infrastructure.repository.AiConfigRepository;
import com.devops.ai.infrastructure.repository.GitLabConfigRepository;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "配置管理", description = "系统配置管理接口")
public class ConfigApiController {

    private final GitLabConfigRepository gitLabConfigRepository;
    private final AiConfigRepository aiConfigRepository;
    private final GitLabService gitLabService;
    private final ConfigEncryptor configEncryptor;

    public ConfigApiController(GitLabConfigRepository gitLabConfigRepository,
                               AiConfigRepository aiConfigRepository,
                               GitLabService gitLabService,
                               ConfigEncryptor configEncryptor) {
        this.gitLabConfigRepository = gitLabConfigRepository;
        this.aiConfigRepository = aiConfigRepository;
        this.gitLabService = gitLabService;
        this.configEncryptor = configEncryptor;
    }

    @GetMapping("/gitlab/config")
    @Operation(summary = "获取 GitLab 配置列表")
    public ResponseEntity<ApiResponse<List<GitLabConfig>>> getGitLabConfigs() {
        return ResponseEntity.ok(ApiResponse.success(gitLabConfigRepository.findAll()));
    }

    @PostMapping("/gitlab/config")
    @Operation(summary = "保存 GitLab 配置")
    public ResponseEntity<ApiResponse<GitLabConfig>> saveGitLabConfig(@RequestBody GitLabConfig config) {
        if (config.getCredentials() != null && !config.getCredentials().isEmpty()) {
            config.setCredentials(configEncryptor.encrypt(config.getCredentials()));
        }
        gitLabConfigRepository.save(config);
        return ResponseEntity.ok(ApiResponse.success("配置已保存", config));
    }

    @GetMapping("/gitlab/config/{id}/test")
    @Operation(summary = "测试 GitLab 连接")
    public ResponseEntity<ApiResponse<String>> testConnection(@PathVariable Long id) {
        GitLabConfig config = gitLabConfigRepository.findById(id).orElse(null);
        if (config == null) {
            return ResponseEntity.status(404).body(ApiResponse.error("配置不存在"));
        }
        boolean connected = gitLabService.testConnection(config);
        if (connected) {
            return ResponseEntity.ok(ApiResponse.success("连接成功"));
        } else {
            return ResponseEntity.ok(ApiResponse.error("连接失败"));
        }
    }

    @GetMapping("/ai/config")
    @Operation(summary = "获取 AI 配置")
    public ResponseEntity<ApiResponse<String>> getAiConfig(@RequestParam String key) {
        AiConfig config = aiConfigRepository.findByConfigKey(key);
        if (config == null) {
            return ResponseEntity.status(404).body(ApiResponse.error("配置项不存在"));
        }
        return ResponseEntity.ok(ApiResponse.success(config.getConfigValue()));
    }

    @PostMapping("/ai/config")
    @Operation(summary = "保存 AI 配置")
    public ResponseEntity<ApiResponse<Void>> saveAiConfig(@RequestParam String key,
                                                          @RequestParam String value,
                                                          @RequestParam(required = false) String description) {
        AiConfig config = aiConfigRepository.findByConfigKey(key);
        if (config == null) {
            config = new AiConfig();
            config.setConfigKey(key);
        }

        if ("llm.apiKey".equals(key)) {
            config.setConfigValue(configEncryptor.encrypt(value));
        } else {
            config.setConfigValue(value);
        }

        if (description != null) {
            config.setDescription(description);
        }
        aiConfigRepository.save(config);
        return ResponseEntity.ok(ApiResponse.success("配置已保存", null));
    }
}
