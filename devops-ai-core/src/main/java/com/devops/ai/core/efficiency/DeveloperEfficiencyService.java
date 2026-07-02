package com.devops.ai.core.efficiency;

import com.devops.ai.core.efficiency.classifier.ChangeIntentClassifier;
import com.devops.ai.core.efficiency.detector.CodeChurnDetector;
import com.devops.ai.core.efficiency.model.*;
import com.devops.ai.core.model.Commit;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

/**
 * 编排完整的开发者效率分析流水线：
 * detect（检测重复修改）→ classify（AI 分类意图）→ aggregate（聚合统计）→ report
 */
@Service
public class DeveloperEfficiencyService {

    private static final Logger log = LoggerFactory.getLogger(DeveloperEfficiencyService.class);

    /** 被修复问题的扣分权重（越高，修复他人的代码对评分影响越大） */
    private static final double FIX_RECEIVED_PENALTY = 2.0;

    private final CodeChurnDetector churnDetector;
    private final ChangeIntentClassifier intentClassifier;
    private final EfficiencyReportGenerator reportGenerator;

    public DeveloperEfficiencyService(CodeChurnDetector churnDetector,
                                       ChangeIntentClassifier intentClassifier,
                                       EfficiencyReportGenerator reportGenerator) {
        this.churnDetector = churnDetector;
        this.intentClassifier = intentClassifier;
        this.reportGenerator = reportGenerator;
    }

    /**
     * 完整分析入口：检测 → 分类 → 聚合 → 生成报告。
     *
     * @return Markdown 格式的分析报告段落（可合并到审查报告末尾）
     */
    public String analyzeAndGenerateReport(List<Commit> commits, ProjectConfig config,
                                            String sinceHash, String untilHash) {
        EfficiencyAnalysisResult result = analyze(commits, config, sinceHash, untilHash);
        return reportGenerator.generate(result);
    }

    /**
     * 执行完整的效率分析，不生成报告。
     */
    public EfficiencyAnalysisResult analyze(List<Commit> commits, ProjectConfig config,
                                             String sinceHash, String untilHash) {
        long startTime = System.currentTimeMillis();
        EfficiencyAnalysisResult result = new EfficiencyAnalysisResult();
        result.setProjectName(config.getName());
        result.setBranch(config.getDefaultBranch());
        result.setCommitRange(sinceHash + ".." + untilHash);

        // Step 1: Ensure repo cloned
        File cloneDir = churnDetector.ensureCloned(config);

        // Step 2: Detect repeated changes
        log.info("Starting churn detection for efficiency analysis...");
        List<RepeatedChange> repeatedChanges = churnDetector.detect(commits, cloneDir, sinceHash, untilHash);
        log.info("Detected {} repeated changes", repeatedChanges.size());

        // Step 3: AI classification
        if (!repeatedChanges.isEmpty()) {
            log.info("Starting AI intent classification for {} repeated changes...", repeatedChanges.size());
            intentClassifier.classifyAll(repeatedChanges);
        }

        result.setRepeatedChanges(repeatedChanges);

        // Step 4: Aggregate per-developer metrics
        Map<String, DeveloperEfficiency> devMap = aggregate(commits, repeatedChanges);
        List<DeveloperEfficiency> efficiencies = new ArrayList<>(devMap.values());
        // Sort by efficiency score descending
        efficiencies.sort((a, b) -> Double.compare(b.getEfficiencyScore(), a.getEfficiencyScore()));
        result.setDeveloperEfficiencies(efficiencies);

        result.setAnalysisTimeMs(System.currentTimeMillis() - startTime);
        log.info("Efficiency analysis complete in {}ms for {} developers",
                result.getAnalysisTimeMs(), efficiencies.size());

        return result;
    }

    /**
     * 聚合开发者指标。
     * 基于提交列表统计基本数量，基于 RepeatedChange 的 AI 分类结果统计 fix/enhance 数量。
     */
    Map<String, DeveloperEfficiency> aggregate(List<Commit> commits,
                                                List<RepeatedChange> repeatedChanges) {
        Map<String, DeveloperEfficiency> devMap = new LinkedHashMap<>();

        // Phase 1: Basic stats from commits
        for (Commit commit : commits) {
            String key = commit.getAuthorName();
            DeveloperEfficiency dev = devMap.computeIfAbsent(key, k -> {
                DeveloperEfficiency d = new DeveloperEfficiency();
                d.setAuthorName(k);
                d.setAuthorEmail(commit.getAuthorEmail());
                return d;
            });
            dev.setTotalCommits(dev.getTotalCommits() + 1);
        }

        // Phase 2: Analyze repeated changes for fix/enhance
        for (RepeatedChange rc : repeatedChanges) {
            ChangeRecord first = rc.getFirstChange();
            ChangeRecord last = rc.getLastChange();

            if (first == null || last == null) continue;

            String firstAuthor = first.getAuthorName();
            String lastAuthor = last.getAuthorName();

            // Increment repeated change count for both
            DeveloperEfficiency firstDev = devMap.computeIfAbsent(firstAuthor, k -> newDev(k, first.getAuthorEmail()));
            DeveloperEfficiency lastDev = devMap.computeIfAbsent(lastAuthor, k -> newDev(k, last.getAuthorEmail()));

            firstDev.setRepeatedChangeCount(firstDev.getRepeatedChangeCount() + 1);
            lastDev.setRepeatedChangeCount(lastDev.getRepeatedChangeCount() + 1);

            // If different authors, analyze intent
            if (!firstAuthor.equals(lastAuthor)) {
                if (rc.isFix()) {
                    // The first author introduced a bug → fixesIntroduced++
                    firstDev.setFixesIntroduced(firstDev.getFixesIntroduced() + 1);
                    // The last author fixed it → fixesMadeForOthers++
                    lastDev.setFixesMadeForOthers(lastDev.getFixesMadeForOthers() + 1);
                } else if (rc.isEnhance()) {
                    // Enhancement by the last author
                    lastDev.setEnhancementsMade(lastDev.getEnhancementsMade() + 1);
                }
                // UNCERTAIN doesn't affect either count
            }
        }

        // Phase 3: Calculate efficiency scores
        for (DeveloperEfficiency dev : devMap.values()) {
            double score = calculateScore(dev);
            dev.setEfficiencyScore(score);
        }

        return devMap;
    }

    /**
     * 计算开发者效率评分。
     * 公式: linesChanged 归一化后的贡献度，减去被修复问题的扣分。
     *
     * 最终公式: baseScore - fixesIntroduced * FIX_RECEIVED_PENALTY + fixesMadeForOthers * 1.5 + enhancementsMade * 1.0
     * baseScore 基于 commit 数量（归一化到 0-100 范围）
     */
    private double calculateScore(DeveloperEfficiency dev) {
        // Base score from commits (0-50)
        double baseScore = Math.min(dev.getTotalCommits() * 2.0, 50.0);

        // Bonus for fixing others (+1.5 per fix)
        double fixBonus = dev.getFixesMadeForOthers() * 1.5;

        // Bonus for enhancements (+1.0 per enhancement)
        double enhanceBonus = dev.getEnhancementsMade() * 1.0;

        // Penalty for introducing bugs that others had to fix (-2.0 per introduced issue)
        double penalty = dev.getFixesIntroduced() * FIX_RECEIVED_PENALTY;

        return Math.max(0, baseScore + fixBonus + enhanceBonus - penalty);
    }

    private DeveloperEfficiency newDev(String name, String email) {
        DeveloperEfficiency d = new DeveloperEfficiency();
        d.setAuthorName(name);
        d.setAuthorEmail(email);
        return d;
    }
}
