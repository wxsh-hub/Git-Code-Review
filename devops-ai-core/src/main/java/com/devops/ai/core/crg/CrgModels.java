package com.devops.ai.core.crg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * code-review-graph (CRG) 数据模型。
 *
 * <p>定义 CRG 与 devops-ai 之间交换的所有数据结构：
 * <ul>
 *   <li>{@link CrgGlobalSummary} — 全局摘要（注入 prompt 用，~576 tokens）</li>
 *   <li>{@link CrgQueryResult} — 图查询结果（callers_of / callees_of）</li>
 *   <li>{@link CrgNode} — 图节点（方法/类/文件）</li>
 *   <li>{@link CrgFanInMethod} — 高扇入方法</li>
 *   <li>{@link CrgFlowSummary} — 执行流摘要</li>
 *   <li>{@link CrgCommunitySummary} — 社区摘要</li>
 *   <li>{@link CrgBuildResult} — 图构建结果</li>
 * </ul>
 */
public final class CrgModels {

    private CrgModels() {}

    // ================================================================
    // 全局摘要（注入 prompt 用）
    // ================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrgGlobalSummary {
        private List<CrgFanInMethod> topFanInMethods = new ArrayList<>();
        private List<CrgFlowSummary> topFlows = new ArrayList<>();
        private List<CrgCommunitySummary> communities = new ArrayList<>();

        public List<CrgFanInMethod> getTopFanInMethods() { return topFanInMethods; }
        public void setTopFanInMethods(List<CrgFanInMethod> v) { this.topFanInMethods = v; }
        public List<CrgFlowSummary> getTopFlows() { return topFlows; }
        public void setTopFlows(List<CrgFlowSummary> v) { this.topFlows = v; }
        public List<CrgCommunitySummary> getCommunities() { return communities; }
        public void setCommunities(List<CrgCommunitySummary> v) { this.communities = v; }
    }

    // ================================================================
    // 图查询结果
    // ================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrgQueryResult {
        private String status;
        private String pattern;
        private String target;
        private String summary;
        private List<CrgNode> results = new ArrayList<>();
        private List<CrgEdge> edges = new ArrayList<>();

        public boolean hasResults() { return results != null && !results.isEmpty(); }

        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }
        public String getPattern() { return pattern; }
        public void setPattern(String v) { this.pattern = v; }
        public String getTarget() { return target; }
        public void setTarget(String v) { this.target = v; }
        public String getSummary() { return summary; }
        public void setSummary(String v) { this.summary = v; }
        public List<CrgNode> getResults() { return results; }
        public void setResults(List<CrgNode> v) { this.results = v; }
        public List<CrgEdge> getEdges() { return edges; }
        public void setEdges(List<CrgEdge> v) { this.edges = v; }
    }

    // ================================================================
    // 图节点
    // ================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrgNode {
        private int id;
        private String kind;
        private String name;
        @JsonProperty("qualified_name")
        private String qualifiedName;
        @JsonProperty("file_path")
        private String filePath;
        @JsonProperty("line_start")
        private Integer lineStart;
        @JsonProperty("line_end")
        private Integer lineEnd;
        private String language;
        @JsonProperty("parent_name")
        private String parentName;
        private String params;
        @JsonProperty("return_type")
        private String returnType;
        private List<String> annotations;

        // getters / setters
        public int getId() { return id; }
        public void setId(int v) { this.id = v; }
        public String getKind() { return kind; }
        public void setKind(String v) { this.kind = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getQualifiedName() { return qualifiedName; }
        public void setQualifiedName(String v) { this.qualifiedName = v; }
        public String getFile() { return filePath; }
        public void setFile(String v) { this.filePath = v; }
        public Integer getLineStart() { return lineStart; }
        public void setLineStart(Integer v) { this.lineStart = v; }
        public Integer getLineEnd() { return lineEnd; }
        public void setLineEnd(Integer v) { this.lineEnd = v; }
        public String getLanguage() { return language; }
        public void setLanguage(String v) { this.language = v; }
        public String getParentName() { return parentName; }
        public void setParentName(String v) { this.parentName = v; }
        public String getParams() { return params; }
        public void setParams(String v) { this.params = v; }
        public String getReturnType() { return returnType; }
        public void setReturnType(String v) { this.returnType = v; }
        public List<String> getAnnotations() { return annotations; }
        public void setAnnotations(List<String> v) { this.annotations = v; }
    }

    // ================================================================
    // 图边
    // ================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrgEdge {
        private String kind;
        @JsonProperty("source_qualified")
        private String sourceQualified;
        @JsonProperty("target_qualified")
        private String targetQualified;
        @JsonProperty("file_path")
        private String filePath;
        private Integer line;

        public String getKind() { return kind; }
        public void setKind(String v) { this.kind = v; }
        public String getSourceQualified() { return sourceQualified; }
        public void setSourceQualified(String v) { this.sourceQualified = v; }
        public String getTargetQualified() { return targetQualified; }
        public void setTargetQualified(String v) { this.targetQualified = v; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String v) { this.filePath = v; }
        public Integer getLine() { return line; }
        public void setLine(Integer v) { this.line = v; }
    }

    // ================================================================
    // 高扇入方法
    // ================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrgFanInMethod {
        private String name;        // ClassName.methodName
        private int callers;        // 调用者数量
        private String file;        // 文件路径
        private Integer line;       // 行号

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public int getCallers() { return callers; }
        public void setCallers(int v) { this.callers = v; }
        public String getFile() { return file; }
        public void setFile(String v) { this.file = v; }
        public Integer getLine() { return line; }
        public void setLine(Integer v) { this.line = v; }
    }

    // ================================================================
    // 执行流摘要
    // ================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrgFlowSummary {
        private int id;
        private String name;
        private int depth;
        @JsonProperty("node_count")
        private int nodeCount;
        @JsonProperty("file_count")
        private int fileCount;
        private double criticality;
        private List<String> pathSteps = new ArrayList<>();

        public int getId() { return id; }
        public void setId(int v) { this.id = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public int getDepth() { return depth; }
        public void setDepth(int v) { this.depth = v; }
        public int getNodeCount() { return nodeCount; }
        public void setNodeCount(int v) { this.nodeCount = v; }
        public int getFileCount() { return fileCount; }
        public void setFileCount(int v) { this.fileCount = v; }
        public double getCriticality() { return criticality; }
        public void setCriticality(double v) { this.criticality = v; }
        public List<String> getPathSteps() { return pathSteps; }
        public void setPathSteps(List<String> v) { this.pathSteps = v; }
    }

    // ================================================================
    // 社区摘要
    // ================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrgCommunitySummary {
        private int id;
        private String name;
        private int size;
        private double cohesion;
        @JsonProperty("dominant_language")
        private String dominantLanguage;

        public int getId() { return id; }
        public void setId(int v) { this.id = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public int getSize() { return size; }
        public void setSize(int v) { this.size = v; }
        public double getCohesion() { return cohesion; }
        public void setCohesion(double v) { this.cohesion = v; }
        public String getDominantLanguage() { return dominantLanguage; }
        public void setDominantLanguage(String v) { this.dominantLanguage = v; }
    }

    // ================================================================
    // 图构建结果
    // ================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrgBuildResult {
        @JsonProperty("files_parsed")
        private int filesParsed;
        @JsonProperty("files_updated")
        private int filesUpdated;
        @JsonProperty("total_nodes")
        private int totalNodes;
        @JsonProperty("total_edges")
        private int totalEdges;
        private List<String> errors = new ArrayList<>();

        public int getFilesParsed() { return filesParsed; }
        public void setFilesParsed(int v) { this.filesParsed = v; }
        public int getFilesUpdated() { return filesUpdated; }
        public void setFilesUpdated(int v) { this.filesUpdated = v; }
        public int getTotalNodes() { return totalNodes; }
        public void setTotalNodes(int v) { this.totalNodes = v; }
        public int getTotalEdges() { return totalEdges; }
        public void setTotalEdges(int v) { this.totalEdges = v; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> v) { this.errors = v; }
    }

    // ================================================================
    // MCP 通用响应结构
    // ================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpResponse {
        private String jsonrpc;
        private Integer id;
        private McpResult result;
        private McpError error;

        public boolean isError() { return error != null; }
        public String getJsonrpc() { return jsonrpc; }
        public void setJsonrpc(String v) { this.jsonrpc = v; }
        public Integer getId() { return id; }
        public void setId(Integer v) { this.id = v; }
        public McpResult getResult() { return result; }
        public void setResult(McpResult v) { this.result = v; }
        public McpError getError() { return error; }
        public void setError(McpError v) { this.error = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpResult {
        private List<McpContent> content;

        public String getText() {
            if (content == null || content.isEmpty()) return null;
            return content.get(0).getText();
        }
        public List<McpContent> getContent() { return content; }
        public void setContent(List<McpContent> v) { this.content = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpContent {
        private String type;
        private String text;

        public String getType() { return type; }
        public void setType(String v) { this.type = v; }
        public String getText() { return text; }
        public void setText(String v) { this.text = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpError {
        private int code;
        private String message;

        public int getCode() { return code; }
        public void setCode(int v) { this.code = v; }
        public String getMessage() { return message; }
        public void setMessage(String v) { this.message = v; }
    }

    // ================================================================
    // 搜索节点结果
    // ================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrgSearchResult {
        private String status;
        private String query;
        @JsonProperty("search_mode")
        private String searchMode;
        private String summary;
        private List<CrgNode> results = new ArrayList<>();

        public boolean hasResults() { return results != null && !results.isEmpty(); }
        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }
        public String getQuery() { return query; }
        public void setQuery(String v) { this.query = v; }
        public String getSearchMode() { return searchMode; }
        public void setSearchMode(String v) { this.searchMode = v; }
        public String getSummary() { return summary; }
        public void setSummary(String v) { this.summary = v; }
        public List<CrgNode> getResults() { return results; }
        public void setResults(List<CrgNode> v) { this.results = v; }
    }
}
