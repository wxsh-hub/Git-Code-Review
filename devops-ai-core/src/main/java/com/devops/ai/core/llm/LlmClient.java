package com.devops.ai.core.llm;

import com.devops.ai.infrastructure.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.util.*;

@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final RestTemplate restTemplate;

    public LlmClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);       // 连接超时 10 秒
        factory.setReadTimeout(120_000);          // 读取超时 120 秒（LLM 响应可慢一些）
        this.restTemplate = new RestTemplate(factory);
    }

    public String call(String provider, String apiKey, String apiUrl, String model, String prompt) {
        return call(provider, apiKey, apiUrl, model, null, prompt, 4096, 0.1, null);
    }

    public String call(String provider, String apiKey, String apiUrl, String model, String prompt, int maxTokens) {
        return call(provider, apiKey, apiUrl, model, null, prompt, maxTokens, 0.1, null);
    }

    /**
     * 带 maxTokens 和 responseFormat 的重载（最常用的 JSON 输出场景）。
     */
    public String call(String provider, String apiKey, String apiUrl, String model,
                       String prompt, int maxTokens, String responseFormat) {
        return call(provider, apiKey, apiUrl, model, null, prompt, maxTokens, 0.1, responseFormat);
    }

    /**
     * Phase 6 — 支持 system prompt + 温度参数的重载。
     *
     * @param systemPrompt 系统提示词，可为 null（null 时只发 user 消息）
     * @param userPrompt   用户提示词
     * @param maxTokens    最大输出 token 数
     * @param temperature  温度 0.0~1.0（Phase 6 review LLM 使用 0.0 确保一致性）
     */
    public String call(String provider, String apiKey, String apiUrl, String model,
                       String systemPrompt, String userPrompt, int maxTokens, double temperature) {
        return call(provider, apiKey, apiUrl, model, systemPrompt, userPrompt, maxTokens, temperature, null);
    }

    /**
     * 支持 response_format 的重载。
     *
     * @param responseFormat null 表示无约束，{@code "json_object"} 要求 LLM 输出合法 JSON
     */
    public String call(String provider, String apiKey, String apiUrl, String model,
                       String systemPrompt, String userPrompt, int maxTokens, double temperature,
                       String responseFormat) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new AiServiceException("API key not configured");
        }

        if ("anthropic".equals(provider)) {
            return callAnthropicApi(apiUrl, apiKey, model, systemPrompt, userPrompt, maxTokens, temperature);
        }
        return callOpenAiCompatibleApi(apiUrl, apiKey, model, systemPrompt, userPrompt, maxTokens, temperature, responseFormat);
    }

    private String callOpenAiCompatibleApi(String baseUrl, String apiKey, String model,
                                           String systemPrompt, String userPrompt,
                                           int maxTokens, double temperature,
                                           String responseFormat) {
        try {
            String url = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

            List<Map<String, Object>> messages = new ArrayList<>();

            // system prompt（Phase 6 review LLM 需要独立指令）
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                Map<String, Object> sysMsg = new LinkedHashMap<>();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemPrompt);
                messages.add(sysMsg);
            }

            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("temperature", temperature);
            requestBody.put("max_tokens", maxTokens);

            // JSON 模式：要求 LLM 输出合法 JSON（OpenAI-compatible API，DeepSeek 支持）
            if (responseFormat != null && !responseFormat.isEmpty()) {
                Map<String, Object> fmt = new LinkedHashMap<>();
                fmt.put("type", responseFormat);
                requestBody.put("response_format", fmt);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> choice = choices.get(0);
                    @SuppressWarnings("unchecked")
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
    private String callAnthropicApi(String baseUrl, String apiKey, String model,
                                     String systemPrompt, String userPrompt,
                                     int maxTokens, double temperature) {
        try {
            String url = baseUrl.replaceAll("/+$", "") + "/v1/messages";

            Map<String, Object> textContent = new LinkedHashMap<>();
            textContent.put("type", "text");
            textContent.put("text", userPrompt);

            Map<String, Object> contentItem = new LinkedHashMap<>();
            contentItem.put("role", "user");
            contentItem.put("content", Collections.singletonList(textContent));

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("temperature", temperature);

            // Anthropic 的 system prompt 是顶层字段
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                requestBody.put("system", systemPrompt);
            }

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
