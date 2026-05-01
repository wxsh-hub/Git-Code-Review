package com.devops.ai.core.classifier;

public interface ClassifierStrategy {

    String getStrategyName();

    ClassificationResult classify(String message);

    boolean isAvailable();
}
