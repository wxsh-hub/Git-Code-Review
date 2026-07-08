package com.devops.ai.core.review.engine;

import com.devops.ai.core.review.ai.ModulePathResolver;
import com.devops.ai.core.review.model.*;
import com.devops.ai.core.review.parser.JavaFileParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 代码架构图分析引擎 — 纯 Java 实现，无需外部 CLI。
 *
 * <p>基于变更文件的 import 关系构建模块依赖图，检测：
 * <ul>
 *   <li>跨模块 import 边（依赖关系图）</li>
 *   <li>模块间循环依赖</li>
 *   <li>分层违规（Controller 直接依赖 DAO/Repository）</li>
 * </ul>
 *
 * <p>输出为 {@link CodeReviewGraph} JSON，作为 LLM 审查的架构背景上下文。</p>
 */
@Component
public class CodeReviewGraphEngine {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewGraphEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 分层违规检测的模式：文件名/路径包含这些关键词
    private static final String[] CONTROLLER_PATTERNS = {"Controller", "controller"};
    private static final String[] DAO_PATTERNS = {"Dao", "DAO", "dao", "Repository", "repository", "Mapper", "mapper"};

    /**
     * 从 diff 列表构建架构分析图（纯 Java，替代外部 CLI）。
     *
     * @param diffs 变更文件列表
     * @return CodeReviewGraph 的 JSON 字符串，失败时返回 null
     */
    public String analyze(List<FileDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            log.debug("No diffs to analyze for graph");
            return null;
        }

        try {
            // 只处理 Java 文件
            List<FileDiff> javaDiffs = diffs.stream()
                    .filter(d -> JavaFileParser.isJavaFile(d.getFilePath()))
                    .filter(d -> d.getNewContent() != null && !d.getNewContent().isEmpty())
                    .collect(Collectors.toList());

            if (javaDiffs.isEmpty()) {
                log.debug("No Java files to analyze for graph");
                return null;
            }

            CodeReviewGraph graph = buildGraph(javaDiffs);
            String json = MAPPER.writeValueAsString(graph);
            log.info("Code graph analysis complete: {} nodes, {} edges, risk={}",
                    graph.getNodes().size(),
                    graph.getEdges() != null ? graph.getEdges().size() : 0,
                    graph.getImpactScope() != null ? graph.getImpactScope().getRiskLevel() : "N/A");
            return json;

        } catch (Exception e) {
            log.warn("Code graph analysis failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 旧接口兼容：通过 repoPath + sinceHash 调用（已废弃，改为调用 analyze(diffs)）。
     *
     * @deprecated 请使用 {@link #analyze(List)} 代替
     */
    @Deprecated
    public String analyze(String repoPath, String sinceHash) {
        log.debug("code-review-graph CLI is deprecated, use analyze(List<FileDiff>) instead");
        return null;
    }

    // ================================================================
    // 图构建
    // ================================================================

    private CodeReviewGraph buildGraph(List<FileDiff> javaDiffs) {
        CodeReviewGraph graph = new CodeReviewGraph();

        // ---- 1. 解析每个文件，构建节点 + 索引 ----
        List<ParsedFile> parsedFiles = new ArrayList<>();
        Map<String, GraphNode> nodeByPath = new LinkedHashMap<>();
        Map<String, String> fqcnToFile = new LinkedHashMap<>();   // FQCN → filePath
        Map<String, String> fileToModule = new LinkedHashMap<>();  // filePath → moduleName

        for (FileDiff diff : javaDiffs) {
            JavaFileParser parser = new JavaFileParser(diff.getNewContent());
            String module = ModulePathResolver.resolveModule(diff.getFilePath());

            // 构建节点
            GraphNode node = new GraphNode();
            node.setId(diff.getFilePath());
            node.setType(determineNodeType(diff.getFilePath()));
            node.setName(extractFileName(diff.getFilePath()));
            node.setFilePath(diff.getFilePath());
            node.setChangeType(diff.getChangeType() != null ? diff.getChangeType() : "MODIFY");
            node.setPackageName(parser.getPackageName());
            graph.addNode(node);
            nodeByPath.put(diff.getFilePath(), node);
            fileToModule.put(diff.getFilePath(), module);

            // 注册 FQCN → filePath 映射
            String pkg = parser.getPackageName();
            if (pkg != null && !pkg.isEmpty()) {
                for (String className : parser.getClassNames()) {
                    fqcnToFile.put(pkg + "." + className, diff.getFilePath());
                }
            }

            parsedFiles.add(new ParsedFile(diff.getFilePath(), module, parser));
        }

        // ---- 2. 构建边：跨文件 import 关系 ----
        for (ParsedFile pf : parsedFiles) {
            for (String imp : pf.parser.getImports()) {
                if (isJdkImport(imp) || !imp.contains(".")) continue;
                // 查找该 import 是否指向另一个变更文件
                String targetFile = fqcnToFile.get(imp);
                if (targetFile != null && !targetFile.equals(pf.filePath)) {
                    GraphEdge edge = new GraphEdge(pf.filePath, targetFile, "imports");
                    graph.addEdge(edge);
                }
            }
        }

        // ---- 3. 构建模块级依赖图 + 检测循环依赖 ----
        Map<String, Set<String>> moduleDeps = buildModuleDependencyGraph(parsedFiles, fqcnToFile, fileToModule);
        List<List<String>> cycles = detectCycles(moduleDeps);

        // ---- 4. 检测分层违规 ----
        List<String> layerViolations = detectLayerViolations(parsedFiles, fqcnToFile, fileToModule);

        // ---- 5. 构建 ImpactScope ----
        ImpactScope scope = new ImpactScope();
        scope.setDirectlyAffectedFiles(new ArrayList<>(nodeByPath.keySet()));
        // 间接影响：被 import 但自身未变更的文件（通过边关系推断）
        Set<String> indirectFiles = new LinkedHashSet<>();
        for (GraphEdge edge : graph.getEdges()) {
            String target = edge.getTargetId();
            if (!nodeByPath.containsKey(target)) {
                indirectFiles.add(target);
            }
        }

        List<String> riskSignals = new ArrayList<>();
        String riskLevel = "LOW";

        if (!cycles.isEmpty()) {
            riskLevel = "HIGH";
            for (List<String> cycle : cycles) {
                riskSignals.add("循环依赖: " + String.join(" → ", cycle) + " → " + cycle.get(0));
            }
        }

        if (!layerViolations.isEmpty()) {
            if (!"HIGH".equals(riskLevel)) riskLevel = "MEDIUM";
            riskSignals.addAll(layerViolations);
        }

        scope.setRiskSignals(riskSignals);
        scope.setRiskLevel(riskLevel);
        graph.setImpactScope(scope);

        return graph;
    }

    // ================================================================
    // 模块依赖图
    // ================================================================

    /**
     * 构建模块级依赖图。
     * 如果模块 A 中的文件 import 了模块 B 中的类，则 A 依赖 B。
     */
    private Map<String, Set<String>> buildModuleDependencyGraph(
            List<ParsedFile> parsedFiles,
            Map<String, String> fqcnToFile,
            Map<String, String> fileToModule) {

        Map<String, Set<String>> deps = new LinkedHashMap<>();

        for (ParsedFile pf : parsedFiles) {
            String fromModule = pf.module;
            deps.computeIfAbsent(fromModule, k -> new LinkedHashSet<>());

            for (String imp : pf.parser.getImports()) {
                if (isJdkImport(imp) || !imp.contains(".")) continue;
                String targetFile = fqcnToFile.get(imp);
                if (targetFile == null) continue;
                String toModule = fileToModule.get(targetFile);
                if (toModule != null && !toModule.equals(fromModule)) {
                    deps.get(fromModule).add(toModule);
                }
            }
        }
        return deps;
    }

    // ================================================================
    // 循环依赖检测（DFS）
    // ================================================================

    /**
     * DFS 检测有向图中的所有环。
     */
    List<List<String>> detectCycles(Map<String, Set<String>> graph) {
        List<List<String>> allCycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        List<String> path = new ArrayList<>();

        for (String node : graph.keySet()) {
            if (!visited.contains(node)) {
                dfsCycles(node, graph, visited, inStack, path, allCycles);
            }
        }
        return allCycles;
    }

    private void dfsCycles(String current, Map<String, Set<String>> graph,
                           Set<String> visited, Set<String> inStack,
                           List<String> path, List<List<String>> allCycles) {
        visited.add(current);
        inStack.add(current);
        path.add(current);

        Set<String> neighbors = graph.getOrDefault(current, Collections.emptySet());
        for (String neighbor : neighbors) {
            if (!visited.contains(neighbor)) {
                dfsCycles(neighbor, graph, visited, inStack, path, allCycles);
            } else if (inStack.contains(neighbor)) {
                // 发现环：从 path 中提取 cycle
                int startIdx = path.indexOf(neighbor);
                if (startIdx >= 0) {
                    List<String> cycle = new ArrayList<>(path.subList(startIdx, path.size()));
                    allCycles.add(cycle);
                }
            }
        }

        path.remove(path.size() - 1);
        inStack.remove(current);
    }

    // ================================================================
    // 分层违规检测
    // ================================================================

    /**
     * 检测 Controller → DAO/Repository/Mapper 的跨层直接依赖。
     */
    List<String> detectLayerViolations(List<ParsedFile> parsedFiles,
                                        Map<String, String> fqcnToFile,
                                        Map<String, String> fileToModule) {
        List<String> violations = new ArrayList<>();

        for (ParsedFile pf : parsedFiles) {
            if (!isController(pf.filePath)) continue;

            for (String imp : pf.parser.getImports()) {
                if (isJdkImport(imp) || !imp.contains(".")) continue;
                String targetFile = fqcnToFile.get(imp);
                if (targetFile == null) continue;

                // 检查被引用的类是否属于 DAO/Repository 层
                if (isDaoLayer(targetFile)) {
                    String targetModule = fileToModule.getOrDefault(targetFile, "?");
                    violations.add(String.format("%s (Controller,%s) 直接依赖 %s (DAO,%s) — 应通过 Service 层隔离",
                            extractFileName(pf.filePath), pf.module,
                            extractFileName(targetFile), targetModule));
                }

                // 检查是否跨模块直接引用 Service（同模块的 Service 引用是正常的）
                String targetModule = fileToModule.get(targetFile);
                if (targetModule != null && !targetModule.equals(pf.module) && isService(targetFile)) {
                    // 跨模块引用 Service 是正常的，不报违规
                    // 但如果跨模块引用的是内部实现类（非接口），则可能是问题
                }
            }
        }
        return violations;
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private String determineNodeType(String filePath) {
        if (isController(filePath)) return "controller";
        if (isService(filePath)) return "service";
        if (isDaoLayer(filePath)) return "dao";
        return "class";
    }

    private boolean isController(String filePath) {
        String lower = filePath.toLowerCase();
        return lower.contains("controller") || filePath.endsWith("Controller.java");
    }

    private boolean isService(String filePath) {
        String lower = filePath.toLowerCase();
        return lower.contains("service") || filePath.endsWith("Service.java")
                || filePath.endsWith("ServiceImpl.java");
    }

    private boolean isDaoLayer(String filePath) {
        String lower = filePath.toLowerCase();
        return lower.contains("dao") || lower.contains("repository") || lower.contains("mapper");
    }

    private boolean isJdkImport(String imp) {
        return imp.startsWith("java.") || imp.startsWith("javax.") || imp.startsWith("sun.")
                || imp.startsWith("jakarta.") || imp.startsWith("org.w3c.");
    }

    private String extractFileName(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        return lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
    }

    // ================================================================
    // 内部数据类
    // ================================================================

    private static class ParsedFile {
        final String filePath;
        final String module;
        final JavaFileParser parser;

        ParsedFile(String filePath, String module, JavaFileParser parser) {
            this.filePath = filePath;
            this.module = module;
            this.parser = parser;
        }
    }
}
