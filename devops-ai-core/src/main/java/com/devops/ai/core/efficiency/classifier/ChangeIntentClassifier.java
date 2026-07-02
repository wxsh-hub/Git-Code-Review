package com.devops.ai.core.efficiency.classifier;

import com.devops.ai.core.efficiency.model.ChangeRecord;
import com.devops.ai.core.efficiency.model.RepeatedChange;
import com.devops.ai.core.llm.LlmClient;
import com.devops.ai.infrastructure.entity.AiConfig;
import com.devops.ai.infrastructure.repository.AiConfigRepository;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用 LLM 分类每个重复修改的意图：是修复前人的错误（FIX），
 * 还是在基础上做功能增强（ENHANCE）。
 *
 * 每个 RepeatedChange 独立调用 LLM，通过 max_tokens=256 控制响应长度。
 * 单次失败不阻塞后续调用，失败条目标记为 UNCERTAIN。
 */
@Component
public class ChangeIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(ChangeIntentClassifier.class);

    private static final Map<String, String> DEFAULT_API_URLS = new LinkedHashMap<>();

    static {
        DEFAULT_API_URLS.put("deepseek", "https://api.deepseek.com");
        DEFAULT_API_URLS.put("openai", "https://api.openai.com");
        DEFAULT_API_URLS.put("anthropic", "https://api.anthropic.com");
    }

    private static final int MAX_TOKENS = 256;
    private static final int MAX_RETRIES = 1;

    private final LlmClient llmClient;
    private final AiConfigRepository aiConfigRepository;
    private final ConfigEncryptor configEncryptor;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public ChangeIntentClassifier(LlmClient llmClient, AiConfigRepository aiConfigRepository,
                                   ConfigEncryptor configEncryptor) {
        this.llmClient = llmClient;
        this.aiConfigRepository = aiConfigRepository;
        this.configEncryptor = configEncryptor;
    }

    /**
     * 对单个 RepeatedChange 进行分类。
     * 成功则设置 intent/confidence/aiReasoning，失败则标记为 UNCERTAIN。
     */
    public void classify(RepeatedChange change) {
        if (change == null || change.getRecords().size() < 2) {
            if (change != null) {
                change.setIntent(RepeatedChange.Intent.UNCERTAIN);
                change.setConfidence(RepeatedChange.Confidence.LOW);
                change.setAiReasoning("记录数不足 (少于 2 条)");
            }
            return;
        }

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                String prompt = buildClassifyPrompt(change);
                String response = callLlm(prompt);
                parseClassifyResponse(response, change);
                return;
            } catch (Exception e) {
                log.warn("Change intent classification attempt {} failed for {}: {}",
                        attempt + 1, change.getFilePath(), e.getMessage());
                if (attempt >= MAX_RETRIES) {
                    change.setIntent(RepeatedChange.Intent.UNCERTAIN);
                    change.setConfidence(RepeatedChange.Confidence.LOW);
                    change.setAiReasoning("AI 分类失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 批量分类——逐个调用 classify()。
     */
    public void classifyAll(java.util.List<RepeatedChange> changes) {
        if (changes == null || changes.isEmpty()) return;

        int total = changes.size();
        int classified = 0;
        for (RepeatedChange change : changes) {
            classify(change);
            classified++;
            if (classified % 5 == 0 || classified == total) {
                log.info("Intent classification progress: {}/{}", classified, total);
            }
        }
    }

    /**
     * 构建精炼的 AI 分类 prompt。
     */
    String buildClassifyPrompt(RepeatedChange change) {
        ChangeRecord first = change.getFirstChange();
        ChangeRecord last = change.getLastChange();

        StringBuilder sb = new StringBuilder();
        sb.append("你是代码变更意图分析专家。同一个代码位置被两个开发者先后修改，请判断第二次修改的意图。\n\n");
        sb.append("文件: ").append(change.getFilePath()).append("\n");
        sb.append("修改1 - ").append(first.getAuthorName())
                .append(" [").append(formatDate(first.getTimestamp())).append("]");
        if (first.getCommitMessage() != null) {
            sb.append(": ").append(truncate(first.getCommitMessage(), 100));
        }
        sb.append("\n```diff\n");
        sb.append(truncate(first.getDiffSnippet(), 2000));
        sb.append("\n```\n\n");

        sb.append("修改2 - ").append(last.getAuthorName())
                .append(" [").append(formatDate(last.getTimestamp())).append("]");
        if (last.getCommitMessage() != null) {
            sb.append(": ").append(truncate(last.getCommitMessage(), 100));
        }
        sb.append("\n```diff\n");
        sb.append(truncate(last.getDiffSnippet(), 2000));
        sb.append("\n```\n\n");

        sb.append("判断第二次修改是修复第一次修改引入的问题，还是在第一次修改基础上做功能增强？\n");
        sb.append("回复格式: 意图|置信度|理由\n");
        sb.append("意图取值: FIX(修复错误) / ENHANCE(功能增强) / UNCERTAIN(不确定)\n");
        sb.append("示例: FIX|HIGH|第二次修改添加了空指针检查，说明第一次修改遗漏了这个边界条件\n");

        return sb.toString();
    }

    /**
     * 解析 LLM 的回复：意图|置信度|理由
     */
    void parseClassifyResponse(String response, RepeatedChange change) {
        if (response == null || response.trim().isEmpty()) {
            change.setIntent(RepeatedChange.Intent.UNCERTAIN);
            change.setConfidence(RepeatedChange.Confidence.LOW);
            change.setAiReasoning("LLM 返回为空");
            return;
        }

        String trimmed = response.trim();
        // Extract the first line that looks like the format: FIX|HIGH|reason...
        String[] lines = trimmed.split("\n");
        String targetLine = null;
        for (String line : lines) {
            String clean = line.trim().replaceAll("^[#*\\-\\s]+", "");
            if (clean.contains("|")) {
                targetLine = clean;
                break;
            }
        }

        if (targetLine == null) {
            targetLine = trimmed;
        }

        String[] parts = targetLine.split("\\|", 3);
        if (parts.length < 2) {
            change.setIntent(RepeatedChange.Intent.UNCERTAIN);
            change.setConfidence(RepeatedChange.Confidence.LOW);
            change.setAiReasoning("无法解析: " + truncated(trimmed, 200));
            return;
        }

        // Parse intent
        String intentStr = parts[0].trim().toUpperCase();
        if (intentStr.contains("FIX") || intentStr.contains("修复")) {
            change.setIntent(RepeatedChange.Intent.FIX);
        } else if (intentStr.contains("ENHANCE") || intentStr.contains("增强") || intentStr.contains("FEATURE")) {
            change.setIntent(RepeatedChange.Intent.ENHANCE);
        } else {
            change.setIntent(RepeatedChange.Intent.UNCERTAIN);
        }

        // Parse confidence
        String confStr = parts[1].trim().toUpperCase();
        if (confStr.contains("HIGH") || confStr.contains("高")) {
            change.setConfidence(RepeatedChange.Confidence.HIGH);
        } else if (confStr.contains("MEDIUM") || confStr.contains("中")) {
            change.setConfidence(RepeatedChange.Confidence.MEDIUM);
        } else {
            change.setConfidence(RepeatedChange.Confidence.LOW);
        }

        // Parse reasoning
        if (parts.length > 2) {
            change.setAiReasoning(parts[2].trim());
        } else {
            change.setAiReasoning("");
        }
    }

    private String callLlm(String prompt) {
        String provider = getProvider();
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) {
            throw new RuntimeException("API key not configured");
        }
        String apiUrl = getApiUrl();
        String model = getModel();
        return llmClient.call(provider, apiKey, apiUrl, model, prompt, MAX_TOKENS);
    }

    private String formatDate(Date date) {
        if (date == null) return "unknown";
        synchronized (dateFormat) {
            return dateFormat.format(date);
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n... [truncated]";
    }

    private String truncated(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    // === Config reading (same pattern as CodeReviewAiService) ===

    private String readConfig(String key) {
        AiConfig config = aiConfigRepository.findByConfigKey(key);
        return config != null ? config.getConfigValue() : "";
    }

    private String getApiKey() {
        String encrypted = readConfig("llm.apiKey");
        if (encrypted == null || encrypted.isEmpty()) return "";
        try {
            return configEncryptor.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("Failed to decrypt API key: {}", e.getMessage());
            return encrypted;
        }
    }

    private String getApiUrl() {
        String customUrl = readConfig("llm.apiUrl");
        if (customUrl != null && !customUrl.trim().isEmpty()) return customUrl.trim();
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
}
