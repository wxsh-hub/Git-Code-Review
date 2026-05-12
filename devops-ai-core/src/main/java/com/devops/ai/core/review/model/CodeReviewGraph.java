package com.devops.ai.core.review.model;

import java.util.ArrayList;
import java.util.List;

public class CodeReviewGraph {
    private List<GraphNode> nodes = new ArrayList<>();
    private List<GraphEdge> edges = new ArrayList<>();
    private ImpactScope impactScope;

    public List<GraphNode> getNodes() { return nodes; }
    public void setNodes(List<GraphNode> nodes) { this.nodes = nodes; }
    public List<GraphEdge> getEdges() { return edges; }
    public void setEdges(List<GraphEdge> edges) { this.edges = edges; }
    public ImpactScope getImpactScope() { return impactScope; }
    public void setImpactScope(ImpactScope impactScope) { this.impactScope = impactScope; }

    public void addNode(GraphNode node) { nodes.add(node); }
    public void addEdge(GraphEdge edge) { edges.add(edge); }
}
