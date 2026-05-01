package com.devops.ai.core.generator;

import com.devops.ai.core.classifier.AiClassifier;
import com.devops.ai.core.classifier.ClassificationResult;
import com.devops.ai.core.classifier.ClassifierService;
import com.devops.ai.core.commit.CommitProcessor;
import com.devops.ai.core.gitlab.GitLabService;
import com.devops.ai.core.incremental.IncrementalManager;
import com.devops.ai.core.model.Category;
import com.devops.ai.core.model.Commit;
import com.devops.ai.core.model.GitLabCommitQuery;
import com.devops.ai.infrastructure.entity.GenerationLog;
import com.devops.ai.infrastructure.repository.GenerationLogRepository;
import cn.hutool.core.util.StrUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
                                  AiClassifier aiClassifier) {
        this.gitLabService = gitLabService;
        this.commitProcessor = commitProcessor;
        this.classifierService = classifierService;
        this.documentGenerator = documentGenerator;
        this.incrementalManager = incrementalManager;
        this.generationLogRepository = generationLogRepository;
        this.aiClassifier = aiClassifier;
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

        if (request.isUseAiClassifier() && aiClassifier.isAvailable()) {
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

        List<ClassificationResult> classificationResults = classifyCommits(commits, request.isUseAiClassifier());

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

        if ("detailed".equals(request.getTemplateName()) && request.isUseAiClassifier() && aiClassifier.isAvailable()) {
            String report = aiClassifier.generateAnalysisReport(categories, request.getProjectName());
            if (report != null && !report.isEmpty()) {
                request.setAnalysisReport(report);
                log.info("AI analysis report generated for detailed template");
            }
        }

        DocumentResult result = documentGenerator.generate(request);

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
}
