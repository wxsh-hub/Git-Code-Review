package com.devops.ai.core.classifier;

import com.devops.ai.core.commit.CommitCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ClassifierServiceImpl implements ClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ClassifierServiceImpl.class);

    private final List<ClassifierStrategy> strategies;
    private final RuleClassifier ruleClassifier;
    private final AiClassifier aiClassifier;
    private final Map<String, String> feedbackStore = new HashMap<>();

    public ClassifierServiceImpl(RuleClassifier ruleClassifier, AiClassifier aiClassifier) {
        this.ruleClassifier = ruleClassifier;
        this.aiClassifier = aiClassifier;

        this.strategies = new ArrayList<>();
        this.strategies.add(aiClassifier);
        this.strategies.add(ruleClassifier);
    }

    @Override
    public ClassificationResult classify(String commitMessage) {
        if (commitMessage == null || commitMessage.trim().isEmpty()) {
            return new ClassificationResult(null, commitMessage, "其他变更");
        }

        String feedbackCategory = feedbackStore.get(commitMessage);
        if (feedbackCategory != null) {
            ClassificationResult result = new ClassificationResult(null, commitMessage, feedbackCategory);
            result.setSource("feedback");
            result.setConfidence(1.0);
            return result;
        }

        ClassificationResult ruleResult = ruleClassifier.classify(commitMessage);
        if (!"其他变更".equals(ruleResult.getCategory())) {
            return ruleResult;
        }

        try {
            if (aiClassifier.isAvailable()) {
                ClassificationResult aiResult = aiClassifier.classify(commitMessage);
                log.debug("AI classified '{}' as '{}'", commitMessage, aiResult.getCategory());
                return aiResult;
            }
        } catch (Exception e) {
            log.warn("AI classification failed, using rule result: {}", e.getMessage(), e);
        }

        return ruleResult;
    }

    @Override
    public List<ClassificationResult> classifyBatch(List<String> commitMessages) {
        return commitMessages.stream()
                .map(this::classify)
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Integer> getCategoryStats(List<ClassificationResult> results) {
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (ClassificationResult result : results) {
            String category = result.getCategory() != null ? result.getCategory() : "其他变更";
            stats.merge(category, 1, Integer::sum);
        }
        return stats;
    }

    @Override
    public void correctClassification(String commitHash, String correctedCategory) {
        log.info("Classification corrected for commit {}: {}", commitHash, correctedCategory);
    }

    @Override
    public void trainWithFeedback(String commitMessage, String correctedCategory) {
        feedbackStore.put(commitMessage, correctedCategory);
        log.info("Feedback stored for message '{}': category '{}'", commitMessage, correctedCategory);
    }

    @Override
    public RuleClassifier getRuleClassifier() {
        return ruleClassifier;
    }
}
