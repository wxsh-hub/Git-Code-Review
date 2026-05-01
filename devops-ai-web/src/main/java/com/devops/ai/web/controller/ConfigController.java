package com.devops.ai.web.controller;

import com.devops.ai.core.gitlab.GitLabService;
import com.devops.ai.infrastructure.entity.AiConfig;
import com.devops.ai.infrastructure.entity.GitLabConfig;
import com.devops.ai.infrastructure.repository.AiConfigRepository;
import com.devops.ai.infrastructure.repository.GitLabConfigRepository;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final GitLabConfigRepository gitLabConfigRepository;
    private final AiConfigRepository aiConfigRepository;
    private final GitLabService gitLabService;
    private final ConfigEncryptor configEncryptor;
    private final RestTemplate restTemplate;

    public ConfigController(GitLabConfigRepository gitLabConfigRepository,
                            AiConfigRepository aiConfigRepository,
                            GitLabService gitLabService,
                            ConfigEncryptor configEncryptor) {
        this.gitLabConfigRepository = gitLabConfigRepository;
        this.aiConfigRepository = aiConfigRepository;
        this.gitLabService = gitLabService;
        this.configEncryptor = configEncryptor;
        this.restTemplate = new RestTemplate();
    }

    @GetMapping("/gitlab")
    public String gitlabConfig(Model model) {
        List<GitLabConfig> configs = gitLabConfigRepository.findAll();
        model.addAttribute("configs", configs);
        return "gitlab-config";
    }

    @PostMapping("/gitlab")
    public String saveGitLabConfig(@ModelAttribute GitLabConfig config,
                                   @RequestParam(required = false) String token,
                                   @RequestParam(required = false) String username,
                                   @RequestParam(required = false) String password,
                                   @RequestParam(required = false, defaultValue = "api") String connectMode,
                                   RedirectAttributes redirectAttributes) {
        try {
            String credentials;
            if ("token".equals(config.getAuthType())) {
                if (token == null || token.trim().isEmpty()) {
                    redirectAttributes.addFlashAttribute("error", "Token 认证模式下 Token 不能为空");
                    return "redirect:/config/gitlab";
                }
                credentials = configEncryptor.encrypt(token.trim());
                config.setConnectMode("api");
            } else {
                String user = username != null ? username.trim() : "";
                String pass = password != null ? password : "";
                if (user.isEmpty() || pass.isEmpty()) {
                    redirectAttributes.addFlashAttribute("error", "用户名和密码不能为空");
                    return "redirect:/config/gitlab";
                }
                credentials = configEncryptor.encrypt(user + ":" + pass);
                config.setConnectMode("clone");
            }
            config.setCredentials(credentials);
            config.setActive(true);
            config.setApiVersion("v4");
            gitLabConfigRepository.save(config);
            log.info("GitLab config saved: {} at {}, authType={}, connectMode={}",
                    config.getName(), config.getGitlabUrl(), config.getAuthType(), config.getConnectMode());
            redirectAttributes.addFlashAttribute("message", "配置保存成功");
        } catch (Exception e) {
            log.error("Failed to save GitLab config: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "配置保存失败: " + e.getMessage());
        }
        return "redirect:/config/gitlab";
    }

    @PostMapping("/gitlab/test")
    @ResponseBody
    public String testConnection(@RequestParam Long id) {
        GitLabConfig config = gitLabConfigRepository.findById(id).orElse(null);
        if (config == null) {
            return "配置不存在";
        }
        try {
            gitLabService.testConnection(config);
            return "连接成功";
        } catch (com.devops.ai.infrastructure.exception.GitLabApiException e) {
            String msg = e.getMessage();
            log.warn("GitLab connection test failed for config id {}: {}", id, msg);
            if (msg != null && msg.contains("Not Found")) {
                if ("clone".equals(config.getConnectMode())) {
                    return "连接失败: Git 仓库地址不正确或未找到。请确认克隆地址正确（例如 https://gitlab.example.com/group/project.git）";
                }
                return "连接失败: GitLab 地址不正确或未找到。请确认 GitLab 地址为服务器根地址（例如 https://gitlab.example.com/），不要填写具体仓库地址";
            }
            if (msg != null && msg.contains("401")) {
                if ("clone".equals(config.getConnectMode())) {
                    return "连接失败: 401 认证失败 - 请检查用户名和密码是否正确";
                }
                return "连接失败: 401 Unauthorized - 认证失败。请确认 Token 是否正确或是否已过期";
            }
            return "连接失败: " + msg;
        } catch (Exception e) {
            log.warn("GitLab connection test failed for config id {}: {}", id, e.getMessage());
            return "连接失败: " + e.getMessage();
        }
    }

    @PostMapping("/gitlab/delete")
    public String deleteGitLabConfig(@RequestParam Long id, RedirectAttributes redirectAttributes) {
        try {
            gitLabConfigRepository.deleteById(id);
            log.info("GitLab config deleted: id={}", id);
            redirectAttributes.addFlashAttribute("message", "配置已删除");
        } catch (Exception e) {
            log.error("Failed to delete GitLab config id {}: {}", id, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "删除配置失败: " + e.getMessage());
        }
        return "redirect:/config/gitlab";
    }

    @GetMapping("/ai")
    public String aiConfig(Model model) {
        AiConfig apiKey = aiConfigRepository.findByConfigKey("llm.apiKey");
        AiConfig provider = aiConfigRepository.findByConfigKey("llm.provider");
        AiConfig apiUrl = aiConfigRepository.findByConfigKey("llm.apiUrl");
        AiConfig modelName = aiConfigRepository.findByConfigKey("llm.modelName");
        AiConfig temperature = aiConfigRepository.findByConfigKey("llm.temperature");

        String decryptedApiKey = "";
        if (apiKey != null && apiKey.getConfigValue() != null && !apiKey.getConfigValue().isEmpty()) {
            try {
                decryptedApiKey = configEncryptor.decrypt(apiKey.getConfigValue());
            } catch (Exception e) {
                decryptedApiKey = apiKey.getConfigValue();
            }
        }

        model.addAttribute("apiKey", decryptedApiKey);
        model.addAttribute("provider", provider != null ? provider.getConfigValue() : "deepseek");
        model.addAttribute("apiUrl", apiUrl != null ? apiUrl.getConfigValue() : "");
        model.addAttribute("modelName", modelName != null ? modelName.getConfigValue() : "deepseek-chat");
        model.addAttribute("temperature", temperature != null ? temperature.getConfigValue() : "0.3");

        return "ai-config";
    }

    @PostMapping("/ai")
    public String saveAiConfig(@RequestParam String apiKey,
                               @RequestParam String provider,
                               @RequestParam(required = false) String apiUrl,
                               @RequestParam String modelName,
                               @RequestParam(defaultValue = "0.3") String temperature,
                               RedirectAttributes redirectAttributes) {
        try {
            saveConfig("llm.apiKey", configEncryptor.encrypt(apiKey), "API 密钥");
            saveConfig("llm.provider", provider, "AI 提供商");
            saveConfig("llm.apiUrl", apiUrl != null ? apiUrl.trim() : "", "自定义请求地址");
            saveConfig("llm.modelName", modelName, "模型 ID");
            saveConfig("llm.temperature", temperature, "温度参数");
            log.info("AI config saved: provider={}, model={}, apiUrl={}", provider, modelName, apiUrl);
            redirectAttributes.addFlashAttribute("message", "AI 配置保存成功");
        } catch (Exception e) {
            log.error("Failed to save AI config: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "AI 配置保存失败: " + e.getMessage());
        }
        return "redirect:/config/ai";
    }

    @PostMapping("/ai/test")
    @ResponseBody
    public String testAiConnection(@RequestParam String provider,
                                   @RequestParam String apiUrl,
                                   @RequestParam String modelName,
                                   @RequestParam String apiKey) {
        try {
            String decryptedKey;
            try {
                decryptedKey = configEncryptor.decrypt(apiKey);
            } catch (Exception e) {
                decryptedKey = apiKey;
            }

            String baseUrl = (apiUrl != null && !apiUrl.trim().isEmpty()) ? apiUrl.trim() : getDefaultApiUrl(provider);
            String model = (modelName != null && !modelName.trim().isEmpty()) ? modelName.trim() : "deepseek-chat";

            String requestJson;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            if ("anthropic".equals(provider)) {
                baseUrl = baseUrl.replaceAll("/+$", "") + "/v1/messages";
                headers.set("x-api-key", decryptedKey);
                headers.set("anthropic-version", "2023-06-01");
                requestJson = "{\"model\":\"" + model + "\",\"max_tokens\":50,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"回复OK\"}]}]}";
            } else {
                baseUrl = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
                headers.setBearerAuth(decryptedKey);
                requestJson = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"回复OK\"}],\"temperature\":0.1,\"max_tokens\":50}";
            }

            HttpEntity<String> request = new HttpEntity<>(requestJson, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(baseUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return "连接成功";
            } else {
                return "连接失败: HTTP " + response.getStatusCodeValue();
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.warn("AI connection test failed: status={}, body={}", e.getRawStatusCode(), e.getResponseBodyAsString());
            if (e.getRawStatusCode() == 401) {
                return "连接失败: 401 Unauthorized - API 密钥无效";
            }
            if (e.getRawStatusCode() == 404) {
                return "连接失败: 404 - 请求地址或模型 ID 不正确";
            }
            return "连接失败: HTTP " + e.getRawStatusCode() + " - " + e.getMessage();
        } catch (Exception e) {
            log.warn("AI connection test failed: {}", e.getMessage());
            return "连接失败: " + e.getMessage();
        }
    }

    private String getDefaultApiUrl(String provider) {
        java.util.Map<String, String> defaults = new java.util.LinkedHashMap<>();
        defaults.put("deepseek", "https://api.deepseek.com");
        defaults.put("openai", "https://api.openai.com");
        defaults.put("anthropic", "https://api.anthropic.com");
        return defaults.getOrDefault(provider, "https://api.deepseek.com");
    }

    private void saveConfig(String key, String value, String description) {
        AiConfig config = aiConfigRepository.findByConfigKey(key);
        if (config == null) {
            config = new AiConfig();
            config.setConfigKey(key);
        }
        config.setConfigValue(value);
        config.setDescription(description);
        aiConfigRepository.save(config);
    }
}
