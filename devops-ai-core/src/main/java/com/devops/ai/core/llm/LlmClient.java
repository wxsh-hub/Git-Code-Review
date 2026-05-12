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

import java.util.*;

@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final RestTemplate restTemplate;

    public LlmClient() {
        this.restTemplate = new RestTemplate();
    }

    public String call(String provider, String apiKey, String apiUrl, String model, String prompt) {
        return call(provider, apiKey, apiUrl, model, prompt, 4096);
    }

    public String call(String provider, String apiKey, String apiUrl, String model, String prompt, int maxTokens) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new AiServiceException("API key not configured");
        }

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
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", maxTokens);

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
