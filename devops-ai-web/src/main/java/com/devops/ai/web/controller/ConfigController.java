package com.devops.ai.web.controller;

import com.devops.ai.core.gitlab.GitLabService;
import com.devops.ai.infrastructure.entity.AiConfig;
import com.devops.ai.infrastructure.repository.AiConfigRepository;
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

import java.util.Map;

@Controller
@RequestMapping("/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final AiConfigRepository aiConfigRepository;
    private final ConfigEncryptor configEncryptor;
    private final RestTemplate restTemplate;

    public ConfigController(AiConfigRepository aiConfigRepository,
                            ConfigEncryptor configEncryptor) {
        this.aiConfigRepository = aiConfigRepository;
        this.configEncryptor = configEncryptor;
        this.restTemplate = new RestTemplate();
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
            saveConfig("llm.apiKey", configEncryptor.encrypt(apiKey), "API Key");
            saveConfig("llm.provider", provider, "AI Provider");
            saveConfig("llm.apiUrl", apiUrl != null ? apiUrl.trim() : "", "Custom API URL");
            saveConfig("llm.modelName", modelName, "Model ID");
            saveConfig("llm.temperature", temperature, "Temperature");
            log.info("AI config saved: provider={}, model={}, apiUrl={}", provider, modelName, apiUrl);
            redirectAttributes.addFlashAttribute("message", "AI configuration saved successfully");
        } catch (Exception e) {
            log.error("Failed to save AI config: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "Failed to save AI config: " + e.getMessage());
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
                requestJson = "{\"model\":\"" + model + "\",\"max_tokens\":50,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"reply OK\"}]}]}";
            } else {
                baseUrl = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
                headers.setBearerAuth(decryptedKey);
                requestJson = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"reply OK\"}],\"temperature\":0.1,\"max_tokens\":50}";
            }

            HttpEntity<String> request = new HttpEntity<>(requestJson, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(baseUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return "Connection successful";
            } else {
                return "Connection failed: HTTP " + response.getStatusCodeValue();
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.warn("AI connection test failed: status={}, body={}", e.getRawStatusCode(), e.getResponseBodyAsString());
            if (e.getRawStatusCode() == 401) {
                return "Connection failed: 401 Unauthorized - invalid API key";
            }
            if (e.getRawStatusCode() == 404) {
                return "Connection failed: 404 - URL or model ID incorrect";
            }
            return "Connection failed: HTTP " + e.getRawStatusCode() + " - " + e.getMessage();
        } catch (Exception e) {
            log.warn("AI connection test failed: {}", e.getMessage());
            return "Connection failed: " + e.getMessage();
        }
    }

    private String getDefaultApiUrl(String provider) {
        Map<String, String> defaults = new java.util.LinkedHashMap<>();
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
