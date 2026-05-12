package com.devops.ai.core.review.model;

import java.util.ArrayList;
import java.util.List;

public class ImpactScope {
    private List<String> directlyAffectedFiles = new ArrayList<>();
    private List<String> indirectlyAffectedFiles = new ArrayList<>();
    private String riskLevel;
    private List<String> riskSignals = new ArrayList<>();

    public List<String> getDirectlyAffectedFiles() { return directlyAffectedFiles; }
    public void setDirectlyAffectedFiles(List<String> directlyAffectedFiles) { this.directlyAffectedFiles = directlyAffectedFiles; }
    public List<String> getIndirectlyAffectedFiles() { return indirectlyAffectedFiles; }
    public void setIndirectlyAffectedFiles(List<String> indirectlyAffectedFiles) { this.indirectlyAffectedFiles = indirectlyAffectedFiles; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public List<String> getRiskSignals() { return riskSignals; }
    public void setRiskSignals(List<String> riskSignals) { this.riskSignals = riskSignals; }
}
