package com.devops.ai.core.generator;

import com.devops.ai.core.classifier.AiClassifier;
import com.devops.ai.core.classifier.ClassificationResult;
import com.devops.ai.core.classifier.ClassifierService;
import com.devops.ai.core.commit.CommitProcessor;
import com.devops.ai.core.gitlab.GitLabService;
import com.devops.ai.core.incremental.IncrementalManager;
import com.devops.ai.core.model.Category;
import com.devops.ai.core.model.Commit;
import com.devops.ai.core.model.ContributorStats;
import com.devops.ai.core.model.GitLabCommitQuery;
import com.devops.ai.core.review.ai.CodeReviewAiService;
import com.devops.ai.core.review.collector.CodeReviewDataCollector;
import com.devops.ai.core.review.engine.CodeReviewGraphEngine;
import com.devops.ai.core.review.model.CodeReviewContext;
import com.devops.ai.core.review.model.CodeReviewGraph;
import com.devops.ai.core.review.model.CodeReviewResult;
import com.devops.ai.core.review.model.FileDiff;
import com.devops.ai.core.review.report.ReviewReportGenerator;
import com.devops.ai.core.efficiency.DeveloperEfficiencyService;
import com.devops.ai.infrastructure.entity.GenerationLog;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.repository.GenerationLogRepository;
import com.devops.ai.infrastructure.repository.ProjectConfigRepository;
import cn.hutool.core.util.StrUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
public class GenerationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GenerationOrchestrator.class);

    private final GitLabService gitLabService;
    private final CommitProcessor commitProcessor;
    private final ClassifierService classifierService;
    private final DocumentGenerator documentGenerator;
    private final IncrementalManager incrementalManager;
    private final GenerationLogRepository generationLogRepository;
    private final AiClassifier aiClassifier;
    private final ProjectConfigRepository projectConfigRepository;
    private final CodeReviewDataCollector codeReviewDataCollector;
    private final CodeReviewGraphEngine codeReviewGraphEngine;
    private final CodeReviewAiService codeReviewAiService;
    private final ReviewReportGenerator reviewReportGenerator;
    private final DeveloperEfficiencyService developerEfficiencyService;

    private static final String[] CATEGORY_ORDER = {
            "新功能", "功能更新", "Bug修复", "代码重构",
            "性能优化", "样式调整", "文档更新", "测试相关",
            "构建相关", "CI配置", "依赖更新", "其他变更"
    };

    private final Map<String, CompletableFuture<Void>> runningTasks = new HashMap<>();

    public GenerationOrchestrator(GitLabService gitLabService,
                                  CommitProcessor commitProcessor,
                                  ClassifierService classifierService,
                                  DocumentGenerator documentGenerator,
                                  IncrementalManager incrementalManager,
                                  GenerationLogRepository generationLogRepository,
                                  AiClassifier aiClassifier,
                                  ProjectConfigRepository projectConfigRepository,
                                  CodeReviewDataCollector codeReviewDataCollector,
                                  CodeReviewGraphEngine codeReviewGraphEngine,
                                  CodeReviewAiService codeReviewAiService,
                                  ReviewReportGenerator reviewReportGenerator,
                                  DeveloperEfficiencyService developerEfficiencyService) {
        this.gitLabService = gitLabService;
        this.commitProcessor = commitProcessor;
        this.classifierService = classifierService;
        this.documentGenerator = documentGenerator;
        this.incrementalManager = incrementalManager;
        this.generationLogRepository = generationLogRepository;
        this.aiClassifier = aiClassifier;
        this.projectConfigRepository = projectConfigRepository;
        this.codeReviewDataCollector = codeReviewDataCollector;
        this.codeReviewGraphEngine = codeReviewGraphEngine;
        this.codeReviewAiService = codeReviewAiService;
        this.reviewReportGenerator = reviewReportGenerator;
        this.developerEfficiencyService = developerEfficiencyService;
    }

    public String submitGeneration(DocumentRequest request) {
        String projectKey = request.getProjectName() != null ? request.getProjectName() : request.getProjectId();
        String safeName = projectKey.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]", "_");
        String taskId = safeName + "_" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());

        GenerationLog logEntry = new GenerationLog();
        logEntry.setProjectId(request.getProjectId());
        logEntry.setTaskId(taskId);
        logEntry.setStatus("pending");
        logEntry.setFormat(request.getFormat() != null ? request.getFormat() : "markdown");
        logEntry.setIncremental(request.isIncremental());
        logEntry.setHasReview(request.isUseCodeReview());
        logEntry.setDimension(request.getDimensions() != null ? String.join(",", request.getDimensions()) : "all");
        generationLogRepository.save(logEntry);

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                logEntry.setStatus("running");
                generationLogRepository.save(logEntry);

                DocumentResult result = generateSync(request);

                logEntry.setStatus(result.isSuccess() ? "completed" : "failed");
                logEntry.setCompletedAt(new Date());
                if (result.isSuccess() && result.getContent() != null) {
                    String extension = "html".equals(result.getFormat()) ? "html" : "md";
                    java.io.File outputDir = new java.io.File("output");
                    if (!outputDir.exists()) {
                        outputDir.mkdirs();
                    }
                    java.io.File outputFile = new java.io.File(outputDir, taskId + "." + extension);
                    java.nio.file.Files.write(outputFile.toPath(), result.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    String outputUrl = "/output/" + taskId + "." + extension;
                    logEntry.setOutputPath(outputUrl);
                    log.info("Document saved to: {} (URL: {})", outputFile.getAbsolutePath(), outputUrl);

                    if ((request.isUseCodeReview() || request.isUseEfficiencyAnalysis()) && result.getReviewContent() != null) {
                        String reviewExtension = "html".equals(request.getReviewFormat()) ? "html" : "md";
                        java.io.File reviewFile = new java.io.File(outputDir, taskId + "_review." + reviewExtension);
                        java.nio.file.Files.write(reviewFile.toPath(), result.getReviewContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        String reviewUrl = "/output/" + taskId + "_review." + reviewExtension;
                        logEntry.setReviewOutputPath(reviewUrl);
                        log.info("Review document saved to: {} (URL: {})", reviewFile.getAbsolutePath(), reviewUrl);
                    }
                }
                if (!result.isSuccess()) {
                    logEntry.setErrorMessage(result.getErrorMessage());
                }
                generationLogRepository.save(logEntry);

            } catch (Exception e) {
                log.error("Generation task {} failed: {}", taskId, e.getMessage(), e);
                logEntry.setStatus("failed");
                logEntry.setCompletedAt(new Date());
                logEntry.setErrorMessage(e.getMessage());
                generationLogRepository.save(logEntry);
            } finally {
                runningTasks.remove(taskId);
            }
        });

        runningTasks.put(taskId, future);
        return taskId;
    }

    public DocumentResult generateSync(DocumentRequest request) {
        GitLabCommitQuery query = buildQuery(request);
        List<Commit> commits;

        if (request.isIncremental()) {
            String formSinceHash = request.getSinceHash();
            String formUntilHash = request.getUntilHash();
            List<Commit> incrementalCommits = incrementalManager.getIncrementalCommits(
                    request.getProjectId(), request.getBranch(), formSinceHash, formUntilHash);
            if (incrementalCommits == null) {
                log.info("No tracker found, performing full generation");
                try {
                    commits = gitLabService.getCommits(query);
                } catch (Exception e) {
                    log.warn("Failed to fetch commits, generating empty document: {}", e.getMessage());
                    commits = Collections.emptyList();
                }
            } else {
                commits = incrementalCommits;
            }
        } else {
            try {
                commits = gitLabService.getCommits(query);
            } catch (Exception e) {
                log.warn("Failed to fetch commits, generating empty document: {}", e.getMessage());
                commits = Collections.emptyList();
            }
        }

        if (request.getUntil() == null || request.getUntil().isEmpty()) {
            request.setUntil(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        }

        if (request.getSince() == null || request.getSince().isEmpty()) {
            if (request.isIncremental()) {
                com.devops.ai.infrastructure.entity.VersionTracker tracker = incrementalManager.getTracker(
                        request.getProjectId(), request.getBranch());
                if (tracker != null && tracker.getLastGenerated() != null) {
                    request.setSince(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(tracker.getLastGenerated()));
                }
            }
            if ((request.getSince() == null || request.getSince().isEmpty()) && commits != null && !commits.isEmpty()) {
                Commit oldest = commits.get(commits.size() - 1);
                if (oldest.getCreatedAt() != null) {
                    request.setSince(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(oldest.getCreatedAt()));
                }
            }
        }

        if (commits == null || commits.isEmpty()) {
            request.setCategories(new ArrayList<>());
            request.setTotalCommits(0);
            request.setTotalAuthors(0);
            return documentGenerator.generate(request);
        }

        commits = commitProcessor.deduplicate(commits);

        // Run AI classifier if requested, or if efficiency analysis needs the categories
        boolean needAiClassify = request.isUseAiClassifier() || request.isUseEfficiencyAnalysis();
        if (needAiClassify && aiClassifier.isAvailable()) {
            List<String> messages = commits.stream()
                    .map(Commit::getMessage)
                    .collect(Collectors.toList());
            List<Boolean> results = aiClassifier.filterWellFormatted(messages);
            List<Commit> filtered = new ArrayList<>();
            for (int i = 0; i < commits.size() && i < results.size(); i++) {
                if (results.get(i)) {
                    filtered.add(commits.get(i));
                }
            }
            log.info("AI filtered commits: {} -> {}", commits.size(), filtered.size());
            commits = filtered;
        }

        if (commits.isEmpty()) {
            request.setCategories(new ArrayList<>());
            request.setTotalCommits(0);
            request.setTotalAuthors(0);
            return documentGenerator.generate(request);
        }

        List<ClassificationResult> classificationResults = classifyCommits(commits, needAiClassify);

        Map<String, List<Commit>> categorizedCommits = new LinkedHashMap<>();
        for (String catName : CATEGORY_ORDER) {
            categorizedCommits.put(catName, new ArrayList<>());
        }

        for (int i = 0; i < commits.size() && i < classificationResults.size(); i++) {
            Commit commit = commits.get(i);
            ClassificationResult cr = classificationResults.get(i);
            String category = cr.getCategory() != null ? cr.getCategory() : "其他变更";

            if (!categorizedCommits.containsKey(category)) {
                category = "其他变更";
            }
            categorizedCommits.get(category).add(commit);
        }

        List<Category> categories = new ArrayList<>();
        int totalCommits = 0;
        Set<String> authors = new HashSet<>();

        for (Map.Entry<String, List<Commit>> entry : categorizedCommits.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                Category cat = new Category(entry.getKey());
                cat.setCommits(entry.getValue());
                categories.add(cat);
                totalCommits += entry.getValue().size();
                for (Commit c : entry.getValue()) {
                    if (c.getAuthorName() != null) {
                        authors.add(c.getAuthorName());
                    }
                }
            }
        }

        request.setCategories(categories);
        request.setTotalCommits(totalCommits);
        request.setTotalAuthors(authors.size());

        // Compute per-contributor stats for the contributor analysis section
        List<ContributorStats> contributorStats = computeContributorStats(categories, totalCommits);
        request.setContributorStats(contributorStats);

        if ("detailed".equals(request.getTemplateName()) && request.isUseAiClassifier() && aiClassifier.isAvailable()) {
            String report = aiClassifier.generateAnalysisReport(categories, request.getProjectName());
            if (report != null && !report.isEmpty()) {
                request.setAnalysisReport(report);
                log.info("AI analysis report generated for detailed template");
            }

            // Optional AI contributor analysis report
            if (!contributorStats.isEmpty()) {
                String contributorReport = aiClassifier.generateContributorAnalysisReport(contributorStats, request.getProjectName());
                if (contributorReport != null && !contributorReport.isEmpty()) {
                    request.setContributorAnalysisReport(contributorReport);
                    log.info("AI contributor analysis report generated for detailed template");
                }
            }
        }

        DocumentResult result = documentGenerator.generate(request);

        if (result.isSuccess() && request.isUseCodeReview()) {
            try {
                ProjectConfig projectConfig = projectConfigRepository.findByProjectCode(request.getProjectName());
                if (projectConfig != null) {
                    String reviewSinceHash = emptyToNull(request.getSinceHash());
                    String reviewUntilHash = emptyToNull(request.getUntilHash());

                    // If no hash range provided, scan the entire project from root commit
                    if (reviewSinceHash == null && reviewUntilHash == null) {
                        File cloneDir = codeReviewDataCollector.resolveCloneDir(projectConfig);
                        // Ensure clone exists by running collectDiffs first
                        if (commits != null && !commits.isEmpty()) {
                            String firstHash = commits.get(0).getId();
                            codeReviewDataCollector.collectDiffs(projectConfig, firstHash, firstHash);
                        }
                        String rootHash = codeReviewDataCollector.getRootCommitHash(cloneDir);
                        if (rootHash != null) {
                            reviewSinceHash = rootHash;
                            reviewUntilHash = "HEAD";
                            log.info("Full project scan: diff from root commit {} to HEAD", rootHash);
                        }
                    } else if (reviewSinceHash == null || reviewUntilHash == null) {
                        // One of the hashes is missing — derive from commits
                        if (commits != null && !commits.isEmpty()) {
                            if (reviewUntilHash == null) reviewUntilHash = commits.get(0).getId();
                            if (reviewSinceHash == null) reviewSinceHash = commits.get(commits.size() - 1).getId();
                        }
                    }

                    if (reviewSinceHash != null && reviewUntilHash != null) {
                        List<FileDiff> diffs = codeReviewDataCollector.collectDiffs(projectConfig, reviewSinceHash, reviewUntilHash);
                        File cloneDir = codeReviewDataCollector.resolveCloneDir(projectConfig);
                        String repoPath = cloneDir.getAbsolutePath();

                        // Run code-review-graph static analysis
                        String graphJson = codeReviewGraphEngine.analyze(repoPath, reviewSinceHash);
                        if (graphJson == null) {
                            log.warn("code-review-graph returned no output, skipping graph analysis");
                        }

                        CodeReviewContext reviewContext = new CodeReviewContext();
                        reviewContext.setProjectName(request.getProjectName());
                        reviewContext.setProjectVersion(request.getProjectVersion());
                        reviewContext.setBranch(request.getBranch());
                        reviewContext.setCommits(commits);
                        reviewContext.setFileDiffs(diffs);
                        reviewContext.setGraphAnalysisJson(graphJson);

                        CodeReviewResult reviewResult = codeReviewAiService.review(reviewContext);

                        String reviewFormat = request.getReviewFormat() != null ? request.getReviewFormat() : "markdown";
                        String reviewContent = reviewReportGenerator.generate(reviewResult, reviewContext, reviewFormat);
                        result.setReviewContent(reviewContent);
                    } else {
                        log.warn("Code review skipped: cannot determine commit hash range");
                    }
                } else {
                    log.warn("Code review skipped: project config not found for {}", request.getProjectName());
                }
            } catch (Exception e) {
                log.error("Code review failed: {}", e.getMessage(), e);
            }
        }

        // Developer efficiency analysis (after code review, merges into review content)
        if (result.isSuccess() && request.isUseEfficiencyAnalysis()) {
            try {
                ProjectConfig projectConfig = projectConfigRepository.findByProjectCode(request.getProjectName());
                if (projectConfig != null) {
                    String reviewSinceHash = emptyToNull(request.getSinceHash());
                    String reviewUntilHash = emptyToNull(request.getUntilHash());
                    // Derive hash range if not specified
                    // For churn detection, sinceHash should be the parent of the oldest commit
                    // so that the oldest commit's changes are included in the walk
                    if (reviewSinceHash == null || reviewUntilHash == null) {
                        if (commits != null && !commits.isEmpty()) {
                            // commits is ordered newest-first (from API), so:
                            // commits.get(0) = newest, commits.get(size-1) = oldest
                            if (reviewUntilHash == null) reviewUntilHash = commits.get(0).getId();
                            // Use parent of the oldest commit as the starting point
                            Commit oldestCommit = commits.get(commits.size() - 1);
                            if (reviewSinceHash == null) {
                                if (oldestCommit.getParentIds() != null && !oldestCommit.getParentIds().isEmpty()) {
                                    reviewSinceHash = oldestCommit.getParentIds().get(0);
                                } else {
                                    // Root commit — include it directly
                                    reviewSinceHash = oldestCommit.getId();
                                }
                            }
                        }
                    }
                    if (reviewSinceHash != null && reviewUntilHash != null) {
                        log.info("Starting developer efficiency analysis (v2 blame): sinceHash={}, untilHash={}",
                                reviewSinceHash.substring(0, Math.min(8, reviewSinceHash.length())),
                                reviewUntilHash.substring(0, Math.min(8, reviewUntilHash.length())));
                        String efficiencySection = developerEfficiencyService.analyzeAndGenerateReport(
                                commits, request.getCategories(), projectConfig, reviewSinceHash, reviewUntilHash);
                        if (efficiencySection != null && !efficiencySection.isEmpty()) {
                            String reviewContent = result.getReviewContent();
                            if (reviewContent != null && !reviewContent.isEmpty()) {
                                result.setReviewContent(reviewContent + "\n\n---\n\n" + efficiencySection);
                            } else {
                                result.setReviewContent("# 开发者效率分析报告\n\n" + efficiencySection);
                            }
                            log.info("Efficiency analysis appended to review report");
                        }
                    } else {
                        log.warn("Efficiency analysis skipped: cannot determine commit hash range");
                    }
                } else {
                    log.warn("Efficiency analysis skipped: project config not found");
                }
            } catch (Exception e) {
                log.error("Efficiency analysis failed: {}", e.getMessage(), e);
                // Don't fail the whole generation — just log the error
            }
        }

        if (result.isSuccess() && request.isIncremental() && !commits.isEmpty()) {
            String latestHash = commits.get(0).getId();
            incrementalManager.updateTracker(request.getProjectId(), request.getBranch(), latestHash);
        }

        return result;
    }

    public String getTaskStatus(String taskId) {
        GenerationLog logEntry = generationLogRepository.findByTaskId(taskId);
        if (logEntry == null) {
            return "not_found";
        }
        return logEntry.getStatus();
    }

    public GenerationLog getTaskLog(String taskId) {
        return generationLogRepository.findByTaskId(taskId);
    }

    private GitLabCommitQuery buildQuery(DocumentRequest request) {
        GitLabCommitQuery query = new GitLabCommitQuery();
        query.setProjectId(request.getProjectId());
        query.setBranch(request.getBranch());
        query.setAuthor(request.getAuthor());

        boolean hasTimeParams = StrUtil.isNotBlank(request.getSince()) || StrUtil.isNotBlank(request.getUntil());
        boolean hasHashParams = StrUtil.isNotBlank(request.getSinceHash()) || StrUtil.isNotBlank(request.getUntilHash());

        if (hasTimeParams && hasHashParams) {
            log.warn("Both time range and hash range are provided, they are mutually exclusive. Preferring hash range.");
            request.setSince(null);
            request.setUntil(null);
        }

        if (hasHashParams) {
            query.setSinceHash(request.getSinceHash());
            query.setUntilHash(request.getUntilHash());
        }

        if (StrUtil.isNotBlank(request.getSince())) {
             Date parsed = tryParseDateTime(request.getSince());
             if (parsed != null) {
                 query.setSince(parsed);
             } else {
                 log.warn("Failed to parse since date: {}", request.getSince());
             }
         }
         if (StrUtil.isNotBlank(request.getUntil())) {
            Date parsed = tryParseDateTime(request.getUntil());
            if (parsed != null) {
                query.setUntil(parsed);
            } else {
                log.warn("Failed to parse until date: {}", request.getUntil());
            }
        }

        return query;
    }

    private Date tryParseDateTime(String value) {
        String[] formats = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
        for (String fmt : formats) {
            try {
                return new SimpleDateFormat(fmt).parse(value);
            } catch (Exception e) {
            }
        }
        return null;
    }

    private List<ClassificationResult> classifyCommits(List<Commit> commits, boolean useAi) {
        List<String> messages = commits.stream()
                .map(Commit::getMessage)
                .collect(Collectors.toList());

        List<ClassificationResult> results;
        if (useAi) {
            results = classifierService.classifyBatch(messages);
        } else {
            results = messages.stream()
                    .map(m -> classifierService.getRuleClassifier().classify(m))
                    .collect(Collectors.toList());
        }

        for (int i = 0; i < results.size() && i < commits.size(); i++) {
            results.get(i).setCommitHash(commits.get(i).getId());
        }

        return results;
    }

    private List<ContributorStats> computeContributorStats(List<Category> categories, int totalCommits) {
        if (totalCommits == 0) return Collections.emptyList();

        // 1. Group by author -> category -> commits
        Map<String, Map<String, List<Commit>>> authorCategoryMap = new LinkedHashMap<>();
        Map<String, String> authorEmails = new LinkedHashMap<>();
        for (Category cat : categories) {
            if (cat.getCommits() == null) continue;
            for (Commit c : cat.getCommits()) {
                String name = c.getAuthorName() != null ? c.getAuthorName() : "未知";
                authorCategoryMap.computeIfAbsent(name, k -> new LinkedHashMap<>())
                        .computeIfAbsent(cat.getName(), k -> new ArrayList<>())
                        .add(c);
                if (c.getAuthorEmail() != null) {
                    authorEmails.putIfAbsent(name, c.getAuthorEmail());
                }
            }
        }

        // 2. Build ContributorStats for each author
        List<ContributorStats> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Commit>>> entry : authorCategoryMap.entrySet()) {
            String authorName = entry.getKey();
            Map<String, List<Commit>> catMap = entry.getValue();

            int count = catMap.values().stream().mapToInt(List::size).sum();
            double pct = (double) count / totalCommits * 100.0;

            // Category distribution
            Map<String, Integer> catDist = new LinkedHashMap<>();
            for (Map.Entry<String, List<Commit>> ce : catMap.entrySet()) {
                catDist.put(ce.getKey(), ce.getValue().size());
            }

            // Commit frequency timeline by date
            Map<String, Integer> frequency = new LinkedHashMap<>();
            for (List<Commit> commits : catMap.values()) {
                for (Commit c : commits) {
                    if (c.getCreatedAt() != null) {
                        String day = new SimpleDateFormat("yyyy-MM-dd").format(c.getCreatedAt());
                        frequency.merge(day, 1, Integer::sum);
                    }
                }
            }
            // Sort by date ascending
            Map<String, Integer> sortedFrequency = new LinkedHashMap<>();
            frequency.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEachOrdered(e -> sortedFrequency.put(e.getKey(), e.getValue()));

            ContributorStats stats = new ContributorStats();
            stats.setAuthorName(authorName);
            stats.setAuthorEmail(authorEmails.get(authorName));
            stats.setCommitCount(count);
            stats.setPercentage(Math.round(pct * 100.0) / 100.0);
            stats.setCategoryDistribution(catDist);
            stats.setCommitFrequency(sortedFrequency);

            // Low-frequency flag and commit details
            boolean lowFreq = pct < 10.0;
            stats.setLowFrequency(lowFreq);
            if (lowFreq) {
                List<ContributorStats.CommitDetail> details = new ArrayList<>();
                for (Map.Entry<String, List<Commit>> ce : catMap.entrySet()) {
                    String catName = ce.getKey();
                    for (Commit c : ce.getValue()) {
                        ContributorStats.CommitDetail cd = new ContributorStats.CommitDetail();
                        cd.setCommitId(c.getId() != null && c.getId().length() >= 8
                                ? c.getId().substring(0, 8) : (c.getId() != null ? c.getId() : ""));
                        cd.setMessage(c.getMessage());
                        cd.setCategory(catName);
                        cd.setDate(c.getCreatedAt() != null
                                ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(c.getCreatedAt()) : "");
                        details.add(cd);
                    }
                }
                stats.setCommitDetails(details);
            }

            result.add(stats);
        }

        // Sort descending by commit count
        result.sort((a, b) -> Integer.compare(b.getCommitCount(), a.getCommitCount()));
        return result;
    }

    /** 将空字符串转为 null，处理 Web 表单提交的空白字段 */
    private String emptyToNull(String s) {
        return (s != null && s.trim().isEmpty()) ? null : s;
    }
}
