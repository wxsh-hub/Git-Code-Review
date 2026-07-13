package com.devops.ai.core.crg;

import com.devops.ai.core.crg.CrgModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CRG (code-review-graph) MCP HTTP 客户端。
 *
 * <p>通过 HTTP JSON-RPC 2.0 调用 CRG MCP 服务的 tool。
 * 内置增量持久化策略：commit hash 缓存复用，避免重复构建。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   crgClient.buildOrUpdateGraph("/path/to/repo");
 *   CrgGlobalSummary summary = crgClient.getGlobalSummary();
 *   CrgQueryResult result = crgClient.queryGraph("callers_of", "qualified::name");
 * }</pre>
 */
@Component
public class CrgClient {

    private static final Logger log = LoggerFactory.getLogger(CrgClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger REQUEST_ID = new AtomicInteger(1);

    /** CRG MCP tool 名称 */
    private static final String TOOL_BUILD = "build_or_update_graph_tool";
    private static final String TOOL_POSTPROCESS = "run_postprocess_tool";
    private static final String TOOL_QUERY_GRAPH = "query_graph_tool";
    private static final String TOOL_SEARCH_NODES = "semantic_search_nodes_tool";
    private static final String TOOL_LIST_FLOWS = "list_flows_tool";
    private static final String TOOL_LIST_COMMUNITIES = "list_communities_tool";
    private static final String TOOL_HUB_NODES = "get_hub_nodes_tool";
    private static final String TOOL_MINIMAL_CONTEXT = "get_minimal_context_tool";
    private static final String TOOL_GRAPH_STATS = "list_graph_stats_tool";

    /** CRG 元数据 key */
    private static final String META_LAST_COMMIT = "last_commit";

    private final CrgConfig config;
    private RestTemplate restTemplate;       // 查询类操作（短超时）
    private RestTemplate buildRestTemplate;  // 构建/后处理（长超时）
    private volatile boolean available;

    public CrgClient(CrgConfig config) {
        this.config = config;
    }

    @PostConstruct
    private void init() {
        SimpleClientHttpRequestFactory queryFactory = new SimpleClientHttpRequestFactory();
        queryFactory.setConnectTimeout(config.getConnectTimeout());
        queryFactory.setReadTimeout(config.getReadTimeout());
        this.restTemplate = new RestTemplate(queryFactory);

        SimpleClientHttpRequestFactory buildFactory = new SimpleClientHttpRequestFactory();
        buildFactory.setConnectTimeout(config.getConnectTimeout());
        buildFactory.setReadTimeout(config.getBuildReadTimeout());
        this.buildRestTemplate = new RestTemplate(buildFactory);

        this.available = checkAvailability();
        log.info("CRG client initialized: enabled={}, url={}, available={}, "
                + "queryTimeout={}s, buildTimeout={}s",
                config.isEnabled(), config.getUrl(), available,
                config.getReadTimeout() / 1000, config.getBuildReadTimeout() / 1000);
    }

    // ================================================================
    // Public API
    // ================================================================

    /**
     * 增量持久化：构建或更新 CRG 代码图。
     *
     * <p>策略：
     * <ol>
     *   <li>无 graph.db → 全量构建，记录 commit hash</li>
     *   <li>有 graph.db 且 commit 相同 → 跳过（复用，<0.1s）</li>
     *   <li>有 graph.db 且 commit 不同 → 增量更新（<1s，几十文件 ~10-20s）</li>
     *   <li>增量失败 → 回退全量构建</li>
     * </ol>
     *
     * @param repoPath 仓库根路径
     */
    public void buildOrUpdateGraph(String repoPath) {
        if (!isReady()) {
            log.info("CRG: not available, skipping graph build for {}", repoPath);
            return;
        }

        String currentCommit = getCurrentCommit(repoPath);
        if (currentCommit == null) {
            log.warn("CRG: cannot get HEAD commit for {}, skipping build", repoPath);
            return;
        }

        // 检查 SQLite 图库是否存在
        java.nio.file.Path dbPath = java.nio.file.Paths.get(repoPath, ".code-review-graph", "graph.db");
        if (!Files.exists(dbPath)) {
            log.info("CRG: no existing graph, running full build for {}", repoPath);
            fullBuildAndPersist(repoPath, currentCommit);
            return;
        }

        // 读取上次构建的 commit hash
        try {
            String lastCommit = getMetadata(repoPath, META_LAST_COMMIT);
            if (currentCommit.equals(lastCommit)) {
                String shortHash = currentCommit.length() > 8 ? currentCommit.substring(0, 8) : currentCommit;
                log.info("CRG: graph up-to-date for {} (commit={}), reusing", repoPath, shortHash);
                return;
            }

            // commit 不同 → 增量更新
            log.info("CRG: commit changed, running incremental update: {} -> {}",
                    shortName(lastCommit), shortName(currentCommit));
            try {
                incrementalUpdate(repoPath, lastCommit);
                runPostProcess(repoPath);
                setMetadata(repoPath, META_LAST_COMMIT, currentCommit);
                log.info("CRG: incremental update successful");
                return;
            } catch (Exception e) {
                log.warn("CRG: incremental update failed ({}), falling back to full build", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("CRG: metadata read failed ({}), running full build", e.getMessage());
        }

        // 增量失败或元数据缺失 → 回退全量构建
        fullBuildAndPersist(repoPath, currentCommit);
    }

    /**
     * 获取全局摘要（~576 tokens）。
     *
     * <p>包含：
     * <ul>
     *   <li>高扇入方法 Top N（Hub 节点）</li>
     *   <li>执行流 Top N（按关键性排序）</li>
     *   <li>社区概览</li>
     * </ul>
     *
     * @return 全局摘要，CRG 不可用时返回 null
     */
    public CrgGlobalSummary getGlobalSummary() {
        if (!isReady()) return null;

        CrgGlobalSummary summary = new CrgGlobalSummary();

        try {
            // 1. Hub 节点 → 高连接度方法
            Map<String, Object> hubArgs = new LinkedHashMap<>();
            hubArgs.put("top_n", config.getTopFaninMethods());
            String hubResp = callTool(TOOL_HUB_NODES, hubArgs, restTemplate);
            if (hubResp != null && !hubResp.isEmpty()) {
                JsonNode hubJson = MAPPER.readTree(hubResp);
                List<CrgFanInMethod> fanInMethods = parseFanInMethods(hubJson);
                summary.setTopFanInMethods(fanInMethods);
                log.debug("CRG: got {} hub nodes for summary", fanInMethods.size());
            }

            // 2. 执行流 Top N
            Map<String, Object> flowArgs = new LinkedHashMap<>();
            flowArgs.put("sort_by", "criticality");
            flowArgs.put("limit", config.getTopFlows());
            String flowResp = callTool(TOOL_LIST_FLOWS, flowArgs, restTemplate);
            if (flowResp != null && !flowResp.isEmpty()) {
                JsonNode flowJson = MAPPER.readTree(flowResp);
                List<CrgFlowSummary> flows = parseFlows(flowJson);
                summary.setTopFlows(flows);
                log.debug("CRG: got {} flows for summary", flows.size());
            }

            // 3. 社区概览
            Map<String, Object> commArgs = new LinkedHashMap<>();
            commArgs.put("sort_by", "size");
            commArgs.put("detail_level", "minimal");
            String commResp = callTool(TOOL_LIST_COMMUNITIES, commArgs, restTemplate);
            if (commResp != null && !commResp.isEmpty()) {
                JsonNode commJson = MAPPER.readTree(commResp);
                List<CrgCommunitySummary> communities = parseCommunities(commJson);
                summary.setCommunities(communities);
                log.debug("CRG: got {} communities for summary", communities.size());
            }

            return summary;
        } catch (Exception e) {
            log.warn("CRG: failed to build global summary: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 图查询：callers_of / callees_of / tests_for。
     *
     * @param pattern 查询模式
     * @param target 目标 qualified_name
     * @return 查询结果，CRG 不可用或无结果时返回 null
     */
    public CrgQueryResult queryGraph(String pattern, String target) {
        if (!isReady()) return null;

        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("pattern", pattern);
            args.put("target", target);
            args.put("detail_level", "standard");

            String resp = callTool(TOOL_QUERY_GRAPH, args, restTemplate);
            if (resp == null || resp.isEmpty()) return null;

            CrgQueryResult result = MAPPER.readValue(resp, CrgQueryResult.class);
            if (result.hasResults() || (result.getEdges() != null && !result.getEdges().isEmpty())) {
                return result;
            }
            return null;  // 空结果 → 让调用方 fallback
        } catch (Exception e) {
            log.warn("CRG: queryGraph({}, {}) failed: {}", pattern, target, e.getMessage());
            return null;
        }
    }

    /**
     * 语义搜索节点（用于符号解析：ClassName.methodName → qualified_name）。
     *
     * @param query 搜索字符串（方法名）
     * @param kind  节点类型过滤（"Function"）
     * @param limit 最大返回数
     * @return 匹配的节点列表，CRG 不可用时返回 null
     */
    public List<CrgNode> searchNodes(String query, String kind, int limit) {
        if (!isReady()) return null;

        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("query", query);
            args.put("kind", kind);
            args.put("limit", limit);
            args.put("detail_level", "standard");

            String resp = callTool(TOOL_SEARCH_NODES, args, restTemplate);
            if (resp == null || resp.isEmpty()) return null;

            CrgSearchResult result = MAPPER.readValue(resp, CrgSearchResult.class);
            return result.hasResults() ? result.getResults() : null;
        } catch (Exception e) {
            log.warn("CRG: searchNodes({}, {}) failed: {}", query, kind, e.getMessage());
            return null;
        }
    }

    /**
     * 检查 CRG 服务是否可用（enabled + connected）。
     */
    public boolean isEnabled() {
        return config.isEnabled() && available;
    }

    // ================================================================
    // 内部：MCP JSON-RPC 通信
    // ================================================================

    /**
     * 向 CRG MCP 服务发送 tools/call 请求，返回 tool 输出的 text 内容。
     *
     * @param toolName    MCP tool 名称
     * @param arguments   tool 参数
     * @param rt          RestTemplate（查询用短超时，构建用长超时）
     * @return tool 返回的 text，失败时返回 null
     */
    private String callTool(String toolName, Map<String, Object> arguments, RestTemplate rt) {
        try {
            ObjectNode request = MAPPER.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", REQUEST_ID.getAndIncrement());
            request.put("method", "tools/call");

            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", MAPPER.valueToTree(arguments));
            request.set("params", params);

            String reqJson = MAPPER.writeValueAsString(request);
            log.debug("CRG MCP -> {}: {}", toolName, truncate(reqJson));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(reqJson, headers);

            ResponseEntity<String> response = rt.postForEntity(
                    config.getUrl() + "/mcp", entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("CRG MCP HTTP {}: {}", response.getStatusCode(), response.getBody());
                return null;
            }

            String respBody = response.getBody();
            if (respBody == null || respBody.isEmpty()) {
                log.warn("CRG MCP: empty response body");
                return null;
            }

            log.debug("CRG MCP <- {}", truncate(respBody));

            // 解析 JSON-RPC response
            McpResponse mcpResp = MAPPER.readValue(respBody, McpResponse.class);
            if (mcpResp.isError()) {
                log.warn("CRG MCP error ({}): {}",
                        mcpResp.getError().getCode(), mcpResp.getError().getMessage());
                return null;
            }

            McpResult result = mcpResp.getResult();
            if (result == null) {
                log.warn("CRG MCP: no result in response");
                return null;
            }

            return result.getText();
        } catch (Exception e) {
            log.warn("CRG MCP call '{}' failed: {}", toolName, e.getMessage());
            return null;
        }
    }

    // ================================================================
    // 内部：构建策略
    // ================================================================

    private void fullBuildAndPersist(String repoPath, String commit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("full_rebuild", true);
        args.put("repo_root", repoPath);
        String resp = callTool(TOOL_BUILD, args, buildRestTemplate);
        if (resp != null) {
            log.info("CRG: full build completed for {}", repoPath);
        }

        // 运行后处理（流程追踪 + 社区检测）
        runPostProcess(repoPath);

        // 持久化 commit hash
        setMetadata(repoPath, META_LAST_COMMIT, commit);
    }

    private void incrementalUpdate(String repoPath, String baseCommit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("full_rebuild", false);
        args.put("repo_root", repoPath);
        args.put("base", baseCommit);
        callTool(TOOL_BUILD, args, buildRestTemplate);
    }

    private void runPostProcess(String repoPath) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("repo_root", repoPath);
        args.put("flows", true);
        args.put("communities", true);
        args.put("fts", true);
        callTool(TOOL_POSTPROCESS, args, buildRestTemplate);
    }

    // ================================================================
    // 内部：CRG 元数据读写
    // ================================================================

    /**
     * 从缓存文件读取元数据。
     *
     * <p>CRG MCP 不直接暴露 metadata 读写 tool，
     * 这里使用 .code-review-graph/.crg_meta.json 文件作为缓存。
     */
    private String getMetadata(String repoPath, String key) {
        try {
            Path metaFile = Paths.get(repoPath, ".code-review-graph", ".crg_meta.json");
            if (!Files.exists(metaFile)) return null;
            String content = new String(Files.readAllBytes(metaFile), StandardCharsets.UTF_8);
            JsonNode meta = MAPPER.readTree(content);
            JsonNode value = meta.get(key);
            return value != null ? value.asText() : null;
        } catch (Exception e) {
            log.debug("CRG: getMetadata({}) failed: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 将元数据写入缓存文件。
     */
    private void setMetadata(String repoPath, String key, String value) {
        try {
            Path metaFile = Paths.get(repoPath, ".code-review-graph", ".crg_meta.json");
            ObjectNode meta;
            if (Files.exists(metaFile)) {
                String content = new String(Files.readAllBytes(metaFile), StandardCharsets.UTF_8);
                meta = (ObjectNode) MAPPER.readTree(content);
            } else {
                Files.createDirectories(metaFile.getParent());
                meta = MAPPER.createObjectNode();
            }
            meta.put(key, value);
            byte[] bytes = MAPPER.writeValueAsString(meta).getBytes(StandardCharsets.UTF_8);
            Files.write(metaFile, bytes);
        } catch (Exception e) {
            log.warn("CRG: setMetadata({}) failed: {}", key, e.getMessage());
        }
    }

    // ================================================================
    // 内部：Commit hash
    // ================================================================

    /**
     * 获取仓库当前 HEAD commit hash（10 秒超时）。
     */
    private String getCurrentCommit(String repoPath) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
            pb.directory(new File(repoPath));
            pb.redirectErrorStream(true);
            p = pb.start();
            String output = readAll(p.getInputStream()).trim();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("CRG: git rev-parse timed out for {}", repoPath);
                return null;
            }
            return p.exitValue() == 0 && !output.isEmpty() ? output : null;
        } catch (Exception e) {
            log.warn("CRG: failed to get HEAD commit: {}", e.getMessage());
            return null;
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    // ================================================================
    // 内部：可用性检查
    // ================================================================

    private boolean checkAvailability() {
        if (!config.isEnabled()) return false;
        try {
            // 简单的连通性检查：调 graph_stats
            Map<String, Object> args = new LinkedHashMap<>();
            String resp = callTool(TOOL_GRAPH_STATS, args, restTemplate);
            return resp != null && !resp.isEmpty();
        } catch (Exception e) {
            log.info("CRG service not available at {}: {}", config.getUrl(), e.getMessage());
            return false;
        }
    }

    private boolean isReady() {
        return isEnabled() && restTemplate != null && buildRestTemplate != null;
    }

    // ================================================================
    // 内部：JSON 解析辅助
    // ================================================================

    @SuppressWarnings("unchecked")
    private List<CrgFanInMethod> parseFanInMethods(JsonNode json) {
        List<CrgFanInMethod> result = new ArrayList<>();
        try {
            List<Map<String, Object>> hubNodes = null;
            if (json.has("hub_nodes")) {
                hubNodes = MAPPER.convertValue(json.get("hub_nodes"), List.class);
            } else if (json.isArray()) {
                hubNodes = MAPPER.convertValue(json, List.class);
            }
            if (hubNodes != null) {
                for (Map<String, Object> node : hubNodes) {
                    CrgFanInMethod m = new CrgFanInMethod();
                    Object name = node.get("name");
                    Object degree = node.get("degree");
                    Object file = node.get("file_path");
                    Object line = node.get("line_start");
                    m.setName(name != null ? name.toString() : "?");
                    m.setCallers(degree instanceof Number ? ((Number) degree).intValue() : 0);
                    m.setFile(file != null ? file.toString() : null);
                    m.setLine(line instanceof Number ? ((Number) line).intValue() : null);
                    result.add(m);
                }
            }
        } catch (Exception e) {
            log.warn("CRG: failed to parse hub nodes: {}", e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<CrgFlowSummary> parseFlows(JsonNode json) {
        List<CrgFlowSummary> result = new ArrayList<>();
        try {
            List<Map<String, Object>> flows = null;
            if (json.has("flows")) {
                flows = MAPPER.convertValue(json.get("flows"), List.class);
            } else if (json.isArray()) {
                flows = MAPPER.convertValue(json, List.class);
            }
            if (flows != null) {
                for (Map<String, Object> f : flows) {
                    CrgFlowSummary flow = new CrgFlowSummary();
                    flow.setId(toInt(f.get("id"), 0));
                    flow.setName(toString(f.get("name"), "?"));
                    flow.setDepth(toInt(f.get("depth"), 0));
                    flow.setNodeCount(toInt(f.get("node_count"), 0));
                    flow.setFileCount(toInt(f.get("file_count"), 0));
                    flow.setCriticality(toDouble(f.get("criticality"), 0.0));
                    // 尝试从 path 字段提取调用链步骤
                    Object path = f.get("path");
                    if (path instanceof List) {
                        List<String> steps = new ArrayList<>();
                        for (Object step : (List<?>) path) {
                            if (step instanceof Map) {
                                Map<String, Object> stepMap = (Map<String, Object>) step;
                                steps.add(toString(stepMap.get("name"), "?"));
                            } else {
                                steps.add(step != null ? step.toString() : "?");
                            }
                        }
                        flow.setPathSteps(steps);
                    }
                    result.add(flow);
                }
            }
        } catch (Exception e) {
            log.warn("CRG: failed to parse flows: {}", e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<CrgCommunitySummary> parseCommunities(JsonNode json) {
        List<CrgCommunitySummary> result = new ArrayList<>();
        try {
            List<Map<String, Object>> communities = null;
            if (json.has("communities")) {
                communities = MAPPER.convertValue(json.get("communities"), List.class);
            } else if (json.isArray()) {
                communities = MAPPER.convertValue(json, List.class);
            }
            if (communities != null) {
                for (Map<String, Object> c : communities) {
                    CrgCommunitySummary comm = new CrgCommunitySummary();
                    comm.setId(toInt(c.get("id"), 0));
                    comm.setName(toString(c.get("name"), "?"));
                    comm.setSize(toInt(c.get("size"), 0));
                    comm.setCohesion(toDouble(c.get("cohesion"), 0.0));
                    comm.setDominantLanguage(toString(c.get("dominant_language"), null));
                    result.add(comm);
                }
            }
        } catch (Exception e) {
            log.warn("CRG: failed to parse communities: {}", e.getMessage());
        }
        return result;
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private static String toString(Object v, String def) {
        return v != null ? v.toString() : def;
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException e) {}
        }
        return def;
    }

    private static double toDouble(Object v, double def) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException e) {}
        }
        return def;
    }

    private static String shortName(String commit) {
        if (commit == null) return "null";
        return commit.length() > 8 ? commit.substring(0, 8) : commit;
    }

    /** Java 8 兼容的 InputStream 全部读取。 */
    private static String readAll(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int n;
        while ((n = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        if (s.length() <= 300) return s;
        return s.substring(0, 300) + "... (" + s.length() + " chars total)";
    }
}
