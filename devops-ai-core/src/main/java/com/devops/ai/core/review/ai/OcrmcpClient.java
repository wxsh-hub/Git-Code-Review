package com.devops.ai.core.review.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.devops.ai.infrastructure.entity.AiConfig;
import com.devops.ai.infrastructure.repository.AiConfigRepository;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OCR MCP server 的 stdio JSON-RPC 客户端。
 *
 * <p>封装子进程生命周期管理（启动 ocr serve）、MCP 握手
 * （initialize → initialized 通知 → tools/call），以及超时与错误处理。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   Map<String, Object> args = new HashMap<>();
 *   args.put("repo_dir", "/path/to/repo");
 *   args.put("path", "src/main/java");
 *   String result = client.callTool("code_scan", args);
 * }</pre>
 */
@Component
public class OcrmcpClient {

    private static final Logger log = LoggerFactory.getLogger(OcrmcpClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger REQUEST_ID = new AtomicInteger(1);

    private final AiConfigRepository aiConfigRepository;
    private final ConfigEncryptor configEncryptor;

    @Value("${ocr.bin.path:}")
    private String ocrBinPath;

    @Value("${ocr.timeout-minutes:30}")
    private int timeoutMinutes;

    @Value("${ocr.model:}")
    private String ocrModel;

    @Value("${ocr.fallback-on-error:true}")
    private boolean fallbackOnError;

    public OcrmcpClient(AiConfigRepository aiConfigRepository, ConfigEncryptor configEncryptor) {
        this.aiConfigRepository = aiConfigRepository;
        this.configEncryptor = configEncryptor;
    }

    // ---- public API ----

    /**
     * 启动 ocr serve 子进程，完成 MCP 握手，调用指定 tool，返回 JSON 文本。
     *
     * @param toolName  要调用的 tool 名称（code_scan / code_review_diff 等）
     * @param arguments tool 的参数 map
     * @return tool 返回的 JSON 文本（即 MCP TextContent）
     * @throws IOException 子进程启动/通信失败时抛出
     */
    public String callTool(String toolName, Map<String, Object> arguments) throws IOException {
        String ocr = resolveOcrBinary();
        log.info("Starting ocr serve: {} (timeout={}min)", ocr, timeoutMinutes);

        ProcessBuilder pb = new ProcessBuilder(ocr, "serve");
        // stderr 重定向到临时文件，便于调试 OCR 内部错误
        File stderrFile = File.createTempFile("ocr-stderr-", ".log");
        stderrFile.deleteOnExit();
        pb.redirectError(stderrFile);

        // 将 devops-ai 的 AI 配置通过环境变量传递给 OCR 子进程
        injectAiConfigEnv(pb.environment());

        Process process = pb.start();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        try {
            // Step 1: MCP initialize
            String initResp = sendRequest(writer, stdout, buildInitialize(), process);
            log.debug("MCP initialize response: {} chars", initResp != null ? initResp.length() : 0);
            if (initResp == null) {
                throw new IOException("MCP initialize returned no response\nOCR stderr:\n" + readStderr(stderrFile));
            }

            // Step 2: Send initialized notification (fire-and-forget)
            sendNotification(writer, "notifications/initialized");

            // Step 3: tools/call
            String result = sendRequest(writer, stdout, buildToolCall(toolName, arguments), process);
            if (result == null) {
                throw new IOException("MCP tools/call returned no response\nOCR stderr:\n" + readStderr(stderrFile));
            }

            JsonNode resultNode = MAPPER.readTree(result);
            if (resultNode.has("error")) {
                JsonNode error = resultNode.get("error");
                String errMsg = error.has("message") ? error.get("message").asText() : error.toString();
                throw new IOException("OCR tool call error: " + errMsg);
            }

            // Extract text content from MCP response
            JsonNode r = resultNode.get("result");
            if (r == null) {
                throw new IOException("MCP response has no 'result' field: " + truncate(result));
            }
            JsonNode content = r.get("content");
            if (content == null || !content.isArray() || content.size() == 0) {
                throw new IOException("MCP response has no 'content' array");
            }
            JsonNode firstContent = content.get(0);
            if (firstContent.has("text")) {
                return firstContent.get("text").asText();
            }
            return firstContent.toString();

        } catch (IOException e) {
            // 在已有 IOException 上追加 OCR stderr 内容（如果尚未包含）
            String msg = e.getMessage();
            if (msg != null && !msg.contains("OCR stderr:")) {
                String stderr = readStderr(stderrFile);
                if (!stderr.isEmpty()) {
                    log.error("OCR stderr output:\n{}", stderr);
                }
            }
            throw e;
        } finally {
            // 确保子进程被清理
            try { writer.close(); } catch (Exception ignored) {}
            try { stdout.close(); } catch (Exception ignored) {}
            process.destroyForcibly();
            try {
                if (!process.waitFor(timeoutMinutes + 1, TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 检查 ocr 是否可用（通过运行 ocr version 验证）。
     */
    public boolean isAvailable() {
        try {
            String ocr = resolveOcrBinary();
            ProcessBuilder pb = new ProcessBuilder(ocr, "version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.info("ocr not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取 ocr 二进制路径。优先级:
     * 1. 系统属性 ocr.bin.path
     * 2. 环境变量 OCR_BIN
     * 3. 配置项 ocr.bin.path
     * 4. PATH 查找
     */
    public String resolveOcrBinary() {
        String path = System.getProperty("ocr.bin.path");
        if (path != null && !path.isEmpty()) return path;

        path = System.getenv("OCR_BIN");
        if (path != null && !path.isEmpty()) return path;

        if (ocrBinPath != null && !ocrBinPath.isEmpty()) return ocrBinPath;

        return "ocr"; // fallback: PATH lookup
    }

    // ---- JSON-RPC frame helpers ----

    /**
     * 发送 JSON-RPC 请求并等待响应。
     *
     * <p>MCP stdio transport 使用换行分隔的 JSON 帧。
     * 对于 tools/call 这类可能耗时较长的请求，会等待完整的响应行。
     * </p>
     */
    private String sendRequest(BufferedWriter writer, BufferedReader stdout,
                               JsonNode request, Process process) throws IOException {
        String reqJson = MAPPER.writeValueAsString(request);
        log.debug("MCP -> {}", truncate(reqJson));
        writer.write(reqJson);
        writer.newLine();
        writer.flush();

        return readResponse(stdout, process);
    }

    /** 发送 MCP 通知（无需等待响应）。 */
    private void sendNotification(BufferedWriter writer, String method) throws IOException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        String notifJson = MAPPER.writeValueAsString(notification);
        log.debug("MCP -> {}", truncate(notifJson));
        writer.write(notifJson);
        writer.newLine();
        writer.flush();
    }

    /**
     * 从 stdout 读取一行 JSON 响应。
     * 超时由子进程整体的 waitFor 控制，这里只关注读行本身。
     */
    private String readResponse(BufferedReader stdout, Process process) throws IOException {
        // 使用单独的线程来支持超时，避免长时间阻塞主线程
        String[] result = new String[1];
        IOException[] ex = new IOException[1];
        boolean[] done = new boolean[1];

        Thread readerThread = new Thread(() -> {
            try {
                String line = stdout.readLine();
                result[0] = line;
                log.debug("MCP <- {}", truncate(line));
            } catch (IOException e) {
                ex[0] = e;
            } finally {
                synchronized (done) {
                    done[0] = true;
                    done.notifyAll();
                }
            }
        }, "ocr-mcp-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        long timeoutMs = TimeUnit.MINUTES.toMillis(timeoutMinutes);
        long deadline = System.currentTimeMillis() + timeoutMs;

        try {
            synchronized (done) {
                while (!done[0]) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        readerThread.interrupt();
                        process.destroyForcibly();
                        throw new IOException("OCR MCP call timed out after " + timeoutMinutes + " minutes");
                    }
                    // 检查子进程是否已死
                    if (!process.isAlive()) {
                        readerThread.interrupt();
                        int exitCode = process.exitValue();
                        throw new IOException("ocr serve exited with code " + exitCode
                                + (exitCode == 0 ? " before producing a response (likely no valid LLM endpoint configured)" : ""));
                    }
                    done.wait(Math.min(remaining, 5000));
                }
            }
        } catch (InterruptedException e) {
            readerThread.interrupt();
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("OCR MCP call interrupted", e);
        }

        if (ex[0] != null) {
            throw ex[0];
        }
        return result[0];
    }

    // ---- MCP message builders ----

    private JsonNode buildInitialize() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", REQUEST_ID.getAndIncrement());
        req.put("method", "initialize");
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "2025-06-18");
        ObjectNode capabilities = MAPPER.createObjectNode();
        params.set("capabilities", capabilities);
        ObjectNode clientInfo = MAPPER.createObjectNode();
        clientInfo.put("name", "devops-ai");
        clientInfo.put("version", "1.0.0");
        params.set("clientInfo", clientInfo);
        req.set("params", params);
        return req;
    }

    private JsonNode buildToolCall(String toolName, Map<String, Object> arguments) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", REQUEST_ID.getAndIncrement());
        req.put("method", "tools/call");
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", toolName);

        // 如果配置了 model 且 arguments 中没有显式指定，则注入默认值
        if (ocrModel != null && !ocrModel.isEmpty() && !arguments.containsKey("model")) {
            arguments.put("model", ocrModel);
        }

        ObjectNode argsNode = MAPPER.valueToTree(arguments);
        params.set("arguments", argsNode);
        req.set("params", params);
        return req;
    }

    // ---- helpers ----

    /**
     * 将 devops-ai 的 AI 配置（provider/apiUrl/apiKey/modelName）注入到 OCR 子进程的环境变量。
     * OCR Go 二进制通过 tryOCREnv() 策略读取这些变量，优先级高于
     * Claude Code 环境变量和 ~/.opencodereview/config.json。
     */
    private void injectAiConfigEnv(Map<String, String> env) {
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("No LLM API key configured — OCR will try its own resolution chain");
            return;
        }

        String apiUrl = getApiUrl();
        String model = getModel();
        String provider = getProvider();

        env.put("OCR_LLM_URL", buildOpenAIEndpoint(apiUrl));
        env.put("OCR_LLM_TOKEN", apiKey);
        env.put("OCR_LLM_MODEL", model);
        // Anthropic 协议是 OCR_USE_ANTHROPIC 默认值，非 Anthropic 时需显式关闭
        if (!"anthropic".equalsIgnoreCase(provider)) {
            env.put("OCR_USE_ANTHROPIC", "false");
        }

        log.info("Injected AI config for OCR subprocess: provider={}, model={}, url={}",
                provider, model, env.get("OCR_LLM_URL"));
    }

    /**
     * 构造 OpenAI 兼容的完整 API endpoint。
     * OCR 的 NewOpenAIClient 会在 URL 不以 /chat/completions 结尾时自动拼接，
     * 因此这里确保传入完整路径避免拼接错误（如 DeepSeek 需 /v1/chat/completions）。
     */
    private String buildOpenAIEndpoint(String baseUrl) {
        String url = baseUrl != null ? baseUrl.trim() : "";
        if (url.isEmpty()) return "";
        if (url.endsWith("/chat/completions")) return url;
        if (url.endsWith("/v1")) return url + "/chat/completions";
        return url + "/v1/chat/completions";
    }

    // ---- AI 配置读取（复用 devops-ai 的 H2 数据库配置） ----

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
        if ("deepseek".equalsIgnoreCase(provider)) return "https://api.deepseek.com";
        if ("openai".equalsIgnoreCase(provider)) return "https://api.openai.com/v1";
        if ("anthropic".equalsIgnoreCase(provider)) return "https://api.anthropic.com";
        return "https://api.deepseek.com";
    }

    private String getModel() {
        String model = readConfig("llm.modelName");
        return (model != null && !model.isEmpty()) ? model : "deepseek-chat";
    }

    private String getProvider() {
        String provider = readConfig("llm.provider");
        return (provider != null && !provider.isEmpty()) ? provider : "deepseek";
    }

    /**
     * 读取 OCR 子进程的 stderr 临时文件内容（最多 4KB），用于调试。
     */
    private String readStderr(File stderrFile) {
        try {
            if (stderrFile.exists() && stderrFile.length() > 0) {
                byte[] bytes = Files.readAllBytes(stderrFile.toPath());
                String content = new String(bytes, StandardCharsets.UTF_8);
                if (content.length() > 4096) {
                    content = content.substring(0, 4096) + "\n... (truncated, " + content.length() + " chars total)";
                }
                return content;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        if (s.length() <= 200) return s;
        return s.substring(0, 200) + "... (" + s.length() + " chars total)";
    }
}
