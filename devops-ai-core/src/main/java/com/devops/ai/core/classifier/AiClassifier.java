package com.devops.ai.core.classifier;

import com.devops.ai.core.llm.LlmClient;
import com.devops.ai.infrastructure.entity.AiConfig;
import com.devops.ai.infrastructure.exception.AiServiceException;
import com.devops.ai.infrastructure.repository.AiConfigRepository;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
public class AiClassifier implements ClassifierStrategy {

    private static final Logger log = LoggerFactory.getLogger(AiClassifier.class);

    private final RestTemplate restTemplate = new RestTemplate();

    private static final Map<String, String> DEFAULT_API_URLS = new LinkedHashMap<>();

    static {
        DEFAULT_API_URLS.put("deepseek", "https://api.deepseek.com");
        DEFAULT_API_URLS.put("openai", "https://api.openai.com");
        DEFAULT_API_URLS.put("anthropic", "https://api.anthropic.com");
    }

    private final LlmClient llmClient;
    private final AiConfigRepository aiConfigRepository;
    private final ConfigEncryptor configEncryptor;

    public AiClassifier(LlmClient llmClient, AiConfigRepository aiConfigRepository, ConfigEncryptor configEncryptor) {
        this.llmClient = llmClient;
        this.aiConfigRepository = aiConfigRepository;
        this.configEncryptor = configEncryptor;
    }

    @Override
    public String getStrategyName() {
        return "ai";
    }

    @Override
    public ClassificationResult classify(String message) {
        try {
            String prompt = buildPrompt(message);
            String response = callLlmApi(prompt);
            String category = parseResponse(response);
            return new ClassificationResult(null, message, category);
        } catch (Exception e) {
            log.warn("AI classification failed, falling back: {}", e.getMessage(), e);
            throw new AiServiceException("AI classification failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            AiConfig apiKeyConfig = aiConfigRepository.findByConfigKey("llm.apiKey");
            if (apiKeyConfig == null || apiKeyConfig.getConfigValue() == null || apiKeyConfig.getConfigValue().isEmpty()) {
                return false;
            }
            String decrypted = configEncryptor.decrypt(apiKeyConfig.getConfigValue());
            return decrypted != null && !decrypted.isEmpty();
        } catch (Exception e) {
            log.warn("Failed to check AI availability: {}", e.getMessage());
            return false;
        }
    }

    private String readConfig(String key) {
        AiConfig config = aiConfigRepository.findByConfigKey(key);
        return config != null ? config.getConfigValue() : "";
    }

    private String getApiKey() {
        String encrypted = readConfig("llm.apiKey");
        if (encrypted == null || encrypted.isEmpty()) {
            return "";
        }
        try {
            return configEncryptor.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("Failed to decrypt API key: {}", e.getMessage());
            return encrypted;
        }
    }

    private String getApiUrl() {
        String customUrl = readConfig("llm.apiUrl");
        if (customUrl != null && !customUrl.trim().isEmpty()) {
            return customUrl.trim();
        }
        String provider = readConfig("llm.provider");
        String defaultUrl = DEFAULT_API_URLS.get(provider);
        return defaultUrl != null ? defaultUrl : "https://api.deepseek.com";
    }

    private String getModel() {
        String model = readConfig("llm.modelName");
        return (model != null && !model.isEmpty()) ? model : "deepseek-chat";
    }

    private String getProvider() {
        String provider = readConfig("llm.provider");
        return (provider != null && !provider.isEmpty()) ? provider : "deepseek";
    }

    protected String buildPrompt(String commitMessage) {
        return "你是一个 Git 提交消息分类专家。请将以下提交消息分类到合适的类别中。\n\n"
                + "可选的分类类别：\n"
                + "1. 新功能 - 新增的功能特性\n"
                + "2. 功能更新 - 对现有功能的修改和更新\n"
                + "3. Bug修复 - 缺陷修复和问题解决\n"
                + "4. 代码重构 - 代码结构调整，功能不变\n"
                + "5. 文档更新 - 文档和注释变更\n"
                + "6. 性能优化 - 性能改进和效率提升\n"
                + "7. 测试相关 - 测试用例和测试框架变更\n"
                + "8. 构建相关 - 构建脚本和配置变更\n"
                + "9. CI配置 - CI/CD 流水线变更\n"
                + "10. 样式调整 - UI 和样式变更\n"
                + "11. 依赖更新 - 依赖库版本更新\n"
                + "12. 其他变更 - 无法归入以上类别的变更\n\n"
                + "请只返回分类名称，不要包含其他内容。\n\n"
                + "提交消息：" + commitMessage;
    }

    protected String callLlmApi(String prompt) {
        String provider = getProvider();
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) {
            throw new AiServiceException("API key not configured");
        }
        String apiUrl = getApiUrl();
        String model = getModel();

        if ("anthropic".equals(provider)) {
            return callAnthropicApi(apiUrl, apiKey, model, prompt);
        }
        return callOpenAiCompatibleApi(apiUrl, apiKey, model, prompt);
    }

    private String callOpenAiCompatibleApi(String baseUrl, String apiKey, String model, String prompt) {
        try {
            String url = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");
            message.put("content", prompt);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", Collections.singletonList(message));
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 100);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getBody() != null) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> respMessage = (Map<String, Object>) choice.get("message");
                    if (respMessage != null && respMessage.get("content") != null) {
                        return respMessage.get("content").toString();
                    }
                }
            }
            throw new AiServiceException("Empty response from LLM API");
        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OpenAI compatible API call failed: {}", e.getMessage());
            throw new AiServiceException("API call failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String callAnthropicApi(String baseUrl, String apiKey, String model, String prompt) {
        try {
            String url = baseUrl.replaceAll("/+$", "") + "/v1/messages";

            Map<String, Object> textContent = new LinkedHashMap<>();
            textContent.put("type", "text");
            textContent.put("text", prompt);

            Map<String, Object> contentItem = new LinkedHashMap<>();
            contentItem.put("role", "user");
            contentItem.put("content", Collections.singletonList(textContent));

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("max_tokens", 100);
            requestBody.put("messages", Collections.singletonList(contentItem));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getBody() != null) {
                List<Map<String, Object>> contentList = (List<Map<String, Object>>) response.getBody().get("content");
                if (contentList != null && !contentList.isEmpty()) {
                    Object text = contentList.get(0).get("text");
                    if (text != null) {
                        return text.toString();
                    }
                }
            }
            throw new AiServiceException("Empty response from Anthropic API");
        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Anthropic API call failed: {}", e.getMessage());
            throw new AiServiceException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    protected String parseResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return "其他变更";
        }
        String trimmed = response.trim();
        String[] validCategories = {"新功能", "功能更新", "Bug修复", "代码重构",
                "文档更新", "性能优化", "测试相关", "构建相关",
                "CI配置", "样式调整", "依赖更新", "其他变更"};
        for (String category : validCategories) {
            if (trimmed.contains(category)) {
                return category;
            }
        }
        return "其他变更";
    }

    public boolean isWellFormatted(String commitMessage) {
        try {
            String prompt = "你是一个 Git 提交消息质量审核专家。请判断以下提交消息是否符合规范的提交格式。\n\n"
                    + "符合规范的提交消息特征：\n"
                    + "1. 以 fix、feat、refactor、docs、update、perf、test、build、ci、style、deps 等前缀开头\n"
                    + "2. 包含冒号（:或：）分隔前缀和描述\n"
                    + "3. 描述内容清晰，能说明变更意图\n"
                    + "4. 是有意义的自然语言描述\n\n"
                    + "不符合规范的提交消息特征：\n"
                    + "1. 纯数字、纯符号或无意义的字符\n"
                    + "2. 空白或仅为标点符号\n"
                    + "3. 无法理解的内容\n"
                    + "4. \"```\"开头的内容\n\n"
                    + "请只回答「通过」或「不通过」，不要包含其他内容。\n\n"
                    + "提交消息：" + commitMessage;

            String response = callLlmApi(prompt);
            return response != null && response.contains("通过");
        } catch (Exception e) {
            log.warn("AI format check failed, defaulting to pass: {}", e.getMessage());
            return true;
        }
    }

    public java.util.List<Boolean> filterWellFormatted(java.util.List<String> messages) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("你是一个 Git 提交消息质量审核专家。以下是一批提交消息，请逐条判断是否符合规范的提交格式。\n\n");
            sb.append("符合规范的提交消息特征：\n");
            sb.append("1. 以 fix、feat、refactor、docs、update、perf、test、build、ci、style、deps 等前缀开头\n");
            sb.append("2. 包含冒号（:或：）分隔前缀和描述\n");
            sb.append("3. 描述内容清晰，能说明变更意图\n");
            sb.append("4. 是有意义的自然语言描述\n\n");
            sb.append("不符合规范的提交消息特征：\n");
            sb.append("1. 纯数字、纯符号或无意义的字符\n");
            sb.append("2. 空白或仅为标点符号\n");
            sb.append("3. 无法理解的内容\n");
            sb.append("4. \"```\"开头的内容\n\n");
            sb.append("请对每条消息只回答「通过」或「不通过」，每行一条，按顺序输出，不要包含其他内容。\n\n");

            for (int i = 0; i < messages.size(); i++) {
                sb.append(i + 1).append(". ").append(messages.get(i)).append("\n");
            }

            String response = callLlmApi(sb.toString(), 2048);
            if (response == null || response.isEmpty()) {
                log.warn("AI batch format check returned empty, passing all");
                java.util.List<Boolean> allPass = new java.util.ArrayList<>();
                for (int i = 0; i < messages.size(); i++) allPass.add(true);
                return allPass;
            }

            java.util.List<Boolean> results = new java.util.ArrayList<>();
            String[] lines = response.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.contains("通过") || trimmed.contains("不通过")) {
                    results.add(trimmed.contains("通过"));
                }
            }

            while (results.size() < messages.size()) {
                results.add(true);
            }

            return results.subList(0, messages.size());
        } catch (Exception e) {
            log.warn("AI batch format check failed, passing all: {}", e.getMessage());
            java.util.List<Boolean> allPass = new java.util.ArrayList<>();
            for (int i = 0; i < messages.size(); i++) allPass.add(true);
            return allPass;
        }
    }

    public String generateAnalysisReport(java.util.List<com.devops.ai.core.model.Category> categories, String projectName) {
        try {
            StringBuilder commitSummary = new StringBuilder();
            for (com.devops.ai.core.model.Category cat : categories) {
                if (cat.getCommits() != null && !cat.getCommits().isEmpty()) {
                    commitSummary.append("## ").append(cat.getName()).append("\n");
                    for (com.devops.ai.core.model.Commit c : cat.getCommits()) {
                        commitSummary.append("- ").append(c.getMessage())
                                .append("（作者：").append(c.getAuthorName()).append("）\n");
                    }
                    commitSummary.append("\n");
                }
            }

            String prompt = "你是一名专业的软件版本更新日志分析专家，擅长从 Git 提交记录中提炼版本价值、贡献分布与技术成果。请基于以下项目信息，生成一份结构清晰、专业简洁的版本分析报告。\n\n"
                    + "项目名称：" + projectName + "\n\n"
                    + "提交记录摘要：\n" + commitSummary.toString() + "\n\n"
                    + "请严格按照以下 4 个模块输出报告，每个模块以 ### 标题开头，内容精炼、客观、工程化，每个模块 2–5 条要点即可：\n\n"
                    + "### 1. 开发者贡献分布\n"
                    + "按提交次数从高到低排序，分析每位开发者的提交量、主要负责模块与贡献领域。\n\n"
                    + "### 2. 提交类型统计\n"
                    + "统计总提交次数，并按类型分类统计：新功能开发、Bug 修复、功能优化、文档更新、构建/配置调整、代码重构等。\n\n"
                    + "### 3. 版本核心价值\n"
                    + "提炼本次版本迭代的核心目标、业务价值与用户价值，总结最关键的改进点。\n\n"
                    + "### 4. 核心开发成果总结\n"
                    + "总结本版本重要功能落地、技术优化、架构改进、性能提升与关键技术突破。\n\n"
                    + "语言要求：专业、正式、简洁客观，避免冗余描述，符合技术报告规范。";

            String response = callLlmApi(prompt, 2000);
            return response != null ? response.trim() : "";
        } catch (Exception e) {
            log.warn("AI analysis report generation failed: {}", e.getMessage());
            return "";
        }
    }

    private String callLlmApi(String prompt, int maxTokens) {
        String provider = getProvider();
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) {
            throw new AiServiceException("API key not configured");
        }
        String apiUrl = getApiUrl();
        String model = getModel();

        if ("anthropic".equals(provider)) {
            return callAnthropicApi(apiUrl, apiKey, model, prompt, maxTokens);
        }
        return callOpenAiCompatibleApi(apiUrl, apiKey, model, prompt, maxTokens);
    }

    private String callOpenAiCompatibleApi(String baseUrl, String apiKey, String model, String prompt, int maxTokens) {
        try {
            String url = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");
            message.put("content", prompt);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", Collections.singletonList(message));
            requestBody.put("temperature", 0.3);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getBody() != null) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> respMessage = (Map<String, Object>) choice.get("message");
                    if (respMessage != null && respMessage.get("content") != null) {
                        return respMessage.get("content").toString();
                    }
                }
            }
            throw new AiServiceException("Empty response from LLM API");
        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OpenAI compatible API call failed: {}", e.getMessage());
            throw new AiServiceException("API call failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String callAnthropicApi(String baseUrl, String apiKey, String model, String prompt, int maxTokens) {
        try {
            String url = baseUrl.replaceAll("/+$", "") + "/v1/messages";

            Map<String, Object> textContent = new LinkedHashMap<>();
            textContent.put("type", "text");
            textContent.put("text", prompt);

            Map<String, Object> contentItem = new LinkedHashMap<>();
            contentItem.put("role", "user");
            contentItem.put("content", Collections.singletonList(textContent));

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("messages", Collections.singletonList(contentItem));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getBody() != null) {
                List<Map<String, Object>> contentList = (List<Map<String, Object>>) response.getBody().get("content");
                if (contentList != null && !contentList.isEmpty()) {
                    Object text = contentList.get(0).get("text");
                    if (text != null) {
                        return text.toString();
                    }
                }
            }
            throw new AiServiceException("Empty response from Anthropic API");
        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Anthropic API call failed: {}", e.getMessage());
            throw new AiServiceException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }
}
