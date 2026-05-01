package com.devops.ai.core.classifier;

import java.util.List;
import java.util.Map;

public interface ClassifierService {

    ClassificationResult classify(String commitMessage);

    List<ClassificationResult> classifyBatch(List<String> commitMessages);

    Map<String, Integer> getCategoryStats(List<ClassificationResult> results);

    void correctClassification(String commitHash, String correctedCategory);

    void trainWithFeedback(String commitMessage, String correctedCategory);

    RuleClassifier getRuleClassifier();
}
