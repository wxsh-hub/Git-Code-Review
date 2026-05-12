package com.devops.ai.core.review.engine;

import com.devops.ai.core.review.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class ImpactAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ImpactAnalyzer.class);

    private static final Set<String> HIGH_RISK_KEYWORDS = new HashSet<>(Arrays.asList(
            "Entity", "Repository", "Controller", "Service", "Resource", "Configuration",
            "Transaction", "Transactional", "Mapper", "Dao"
    ));

    public ImpactScope analyze(CodeReviewGraph graph) {
        ImpactScope scope = new ImpactScope();
        Set<String> directFiles = new LinkedHashSet<>();
        Set<String> indirectFiles = new LinkedHashSet<>();
        List<String> riskSignals = new ArrayList<>();

        // Identify directly changed file nodes
        Set<String> changedNodeIds = new HashSet<>();
        for (GraphNode node : graph.getNodes()) {
            if (!"unchanged".equals(node.getChangeType()) && "file".equals(node.getType())) {
                directFiles.add(node.getFilePath());
                changedNodeIds.add(node.getId());
            }
        }

        // Find files that import changed files (reverse dependency)
        for (GraphEdge edge : graph.getEdges()) {
            if ("imports".equals(edge.getRelationType()) && changedNodeIds.contains(edge.getTargetId())) {
                indirectFiles.add(edge.getSourceId());
            }
        }
        indirectFiles.removeAll(directFiles);

        // Detect risk signals
        for (GraphNode node : graph.getNodes()) {
            if (!"unchanged".equals(node.getChangeType())) {
                String name = node.getName();
                if (HIGH_RISK_KEYWORDS.stream().anyMatch(kw -> name != null && name.contains(kw))) {
                    riskSignals.add("Changed: " + name + " (" + node.getFilePath() + ")");
                }
            }
        }

        // Check for public API changes
        for (GraphNode node : graph.getNodes()) {
            if ("class".equals(node.getType()) && !"unchanged".equals(node.getChangeType())) {
                riskSignals.add("Class changed: " + node.getName() + " in " + node.getFilePath());
            }
        }

        // Determine risk level
        if (riskSignals.size() >= 3) {
            scope.setRiskLevel("高");
        } else if (riskSignals.size() >= 1) {
            scope.setRiskLevel("中");
        } else {
            scope.setRiskLevel("低");
        }

        scope.setDirectlyAffectedFiles(new ArrayList<>(directFiles));
        scope.setIndirectlyAffectedFiles(new ArrayList<>(indirectFiles));
        scope.setRiskSignals(riskSignals);

        log.info("Impact analysis: {} direct, {} indirect files, risk={}",
                directFiles.size(), indirectFiles.size(), scope.getRiskLevel());
        return scope;
    }
}
