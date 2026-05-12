package com.devops.ai.core.review.engine;

import com.devops.ai.core.review.model.*;
import com.devops.ai.core.review.parser.JavaFileParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class GraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(GraphBuilder.class);

    public CodeReviewGraph build(List<FileDiff> fileDiffs) {
        CodeReviewGraph graph = new CodeReviewGraph();
        Map<String, GraphNode> nodeMap = new LinkedHashMap<>();
        List<GraphEdge> edges = new ArrayList<>();

        // Build file-level nodes
        for (FileDiff diff : fileDiffs) {
            String filePath = diff.getFilePath();
            String nodeId = "file:" + filePath;
            GraphNode node = new GraphNode(nodeId, "file", extractFileName(filePath), filePath, diff.getChangeType());

            // Parse Java source for detailed info
            if (JavaFileParser.isJavaFile(filePath) && diff.getNewContent() != null) {
                JavaFileParser parser = new JavaFileParser(diff.getNewContent());
                node.setPackageName(parser.getPackageName());

                // Create class-level child nodes
                for (String className : parser.getClassNames()) {
                    String classId = "class:" + filePath + "#" + className;
                    GraphNode classNode = new GraphNode(classId, "class", className, filePath, diff.getChangeType());
                    classNode.setPackageName(parser.getPackageName());
                    nodeMap.put(classId, classNode);

                    edges.add(new GraphEdge(nodeId, classId, "contains"));
                }

                // Build dependency edges from imports
                for (String imp : parser.getImports()) {
                    if (imp.startsWith("java.") || imp.startsWith("javax.") || imp.startsWith("sun.")) {
                        continue; // skip JDK internal deps
                    }
                    String depId = "dep:" + imp;
                    edges.add(new GraphEdge(nodeId, depId, "imports"));

                    // Create a minimal node for the dependency
                    if (!nodeMap.containsKey(depId)) {
                        GraphNode depNode = new GraphNode(depId, "external", imp, "", "unchanged");
                        depNode.setPackageName(imp.contains(".") ? imp.substring(0, imp.lastIndexOf('.')) : "");
                        nodeMap.put(depId, depNode);
                    }
                }
            }

            nodeMap.put(nodeId, node);
        }

        graph.setNodes(new ArrayList<>(nodeMap.values()));
        graph.setEdges(edges);
        log.info("Graph built: {} nodes, {} edges", nodeMap.size(), edges.size());
        return graph;
    }

    private String extractFileName(String filePath) {
        if (filePath == null) return "";
        int slash = filePath.lastIndexOf('/');
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }
}
