package com.devops.ai.api.controller;

import com.devops.ai.api.dto.*;
import com.devops.ai.core.generator.DocumentRequest;
import com.devops.ai.core.generator.GenerationOrchestrator;
import com.devops.ai.infrastructure.entity.GenerationLog;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.repository.ProjectConfigRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cn.hutool.core.util.StrUtil;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "文档生成", description = "文档生成相关接口")
public class DocumentApiController {

    private final GenerationOrchestrator orchestrator;
    private final ProjectConfigRepository projectConfigRepository;

    public DocumentApiController(GenerationOrchestrator orchestrator,
                                 ProjectConfigRepository projectConfigRepository) {
        this.orchestrator = orchestrator;
        this.projectConfigRepository = projectConfigRepository;
    }

    @GetMapping("/generate")
    @Operation(summary = "触发文档生成", description = "通过GET请求提交文档生成任务。sync=true时同步等待并直接返回文件；sync=false（默认）时异步执行，返回taskId。")
    public ResponseEntity<?> generateGet(
            @RequestParam String projectCode,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false, defaultValue = "markdown") String format,
            @RequestParam(required = false) String projectVersion,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String sinceHash,
            @RequestParam(required = false) String templateName,
            @RequestParam(required = false, defaultValue = "false") boolean incremental,
            @RequestParam(required = false, defaultValue = "false") boolean useAiClassifier,
            @RequestParam(required = false, defaultValue = "false") boolean sync,
            @RequestParam(required = false, defaultValue = "false") boolean useCodeReview,
            @RequestParam(required = false, defaultValue = "false") boolean useEfficiencyAnalysis,
            @RequestParam(required = false, defaultValue = "markdown") String reviewFormat) {

        if (StrUtil.isBlank(projectCode)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("projectCode不能为空，必须指定项目编码"));
        }

        ProjectConfig project = projectConfigRepository.findByProjectCode(projectCode);
        if (project == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("项目编码不存在: " + projectCode));
        }

        branch = normalizeBranch(branch);

        String finalBranch = branch;
        if (StrUtil.isBlank(finalBranch) && StrUtil.isNotBlank(project.getDefaultBranch())) {
            finalBranch = project.getDefaultBranch();
        }

        String finalTemplateName = templateName;
        if (StrUtil.isBlank(finalTemplateName)) {
            if (StrUtil.isNotBlank(project.getTemplateName())) {
                finalTemplateName = project.getTemplateName();
            } else {
                finalTemplateName = "standard";
            }
        }

        DocumentRequest docRequest = new DocumentRequest();
        docRequest.setProjectId(project.getProjectId());
        docRequest.setProjectName(project.getProjectCode());
        docRequest.setBranch(finalBranch);
        docRequest.setTemplateName(finalTemplateName);
        docRequest.setFormat(format);
        docRequest.setSinceHash(sinceHash);
        docRequest.setProjectVersion(projectVersion);
        docRequest.setAuthor(author);
        docRequest.setIncremental(incremental);
        docRequest.setUseAiClassifier(useAiClassifier);
        docRequest.setUseCodeReview(useCodeReview);
        docRequest.setUseEfficiencyAnalysis(useEfficiencyAnalysis);
        docRequest.setReviewFormat(reviewFormat);

        if (StrUtil.isBlank(docRequest.getUntil())) {
            docRequest.setUntil(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        }

        String taskId = orchestrator.submitGeneration(docRequest);

        if (!sync) {
            TaskResponse taskResponse = new TaskResponse(taskId, "pending", "5s");
            return ResponseEntity.ok(ApiResponse.success("文档生成任务已提交", taskResponse));
        }

        // sync = true: 轮询等待完成，然后直接返回文件（最长等待1小时）
        long deadline = System.currentTimeMillis() + 3_600_000;
        while (System.currentTimeMillis() < deadline) {
            GenerationLog logEntry = orchestrator.getTaskLog(taskId);
            if (logEntry == null) {
                return ResponseEntity.status(500)
                        .body(ApiResponse.error("任务提交失败"));
            }
            if ("completed".equals(logEntry.getStatus())) {
                return downloadFileResponse(logEntry);
            }
            if ("failed".equals(logEntry.getStatus())) {
                return ResponseEntity.status(500)
                        .body(ApiResponse.error("文档生成失败: " + logEntry.getErrorMessage()));
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ResponseEntity.status(408)
                .body(ApiResponse.error("文档生成超时，请稍后通过 /api/v1/tasks/" + taskId + "/download 获取文件"));
    }

    @PostMapping("/generate")
    @Operation(summary = "触发文档生成", description = "提交文档生成任务，异步执行。必须传入projectCode。")
    public ResponseEntity<ApiResponse<TaskResponse>> generate(
            @RequestBody GenerateRequest request) {

        if (StrUtil.isBlank(request.getProjectCode())) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("projectCode不能为空，必须指定项目编码"));
        }

        ProjectConfig project = projectConfigRepository.findByProjectCode(request.getProjectCode());
        if (project == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("项目编码不存在: " + request.getProjectCode()));
        }

        String requestBranch = request.getBranch();
        if (StrUtil.isNotBlank(requestBranch)) {
            request.setBranch(normalizeBranch(requestBranch));
        }
        if (StrUtil.isBlank(request.getBranch()) && StrUtil.isNotBlank(project.getDefaultBranch())) {
            request.setBranch(project.getDefaultBranch());
        }
        if (StrUtil.isBlank(request.getTemplateName())) {
            if (StrUtil.isNotBlank(project.getTemplateName())) {
                request.setTemplateName(project.getTemplateName());
            } else {
                request.setTemplateName("standard");
            }
        }

        boolean hasTimeParams = StrUtil.isNotBlank(request.getSince()) || StrUtil.isNotBlank(request.getUntil());
        boolean hasHashParams = StrUtil.isNotBlank(request.getSinceHash()) || StrUtil.isNotBlank(request.getUntilHash());
        if (hasTimeParams && hasHashParams) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("时间范围和Hash范围不能同时使用，请选择其中一种筛选方式"));
        }

        DocumentRequest docRequest = new DocumentRequest();
        docRequest.setProjectId(project.getProjectId());
        docRequest.setProjectName(project.getProjectCode());
        docRequest.setBranch(request.getBranch());
        docRequest.setTemplateName(request.getTemplateName());
        docRequest.setFormat(request.getFormat());
        docRequest.setProjectVersion(request.getProjectVersion());
        docRequest.setSince(request.getSince());
        docRequest.setUntil(request.getUntil());
        docRequest.setAuthor(request.getAuthor());
        docRequest.setSinceHash(request.getSinceHash());
        docRequest.setUntilHash(request.getUntilHash());
        docRequest.setDimensions(request.getDimensions());
        docRequest.setIncremental(request.isIncremental());
        docRequest.setUseAiClassifier(request.isUseAiClassifier());
        docRequest.setUseCodeReview(request.isUseCodeReview());
        docRequest.setReviewFormat(request.getReviewFormat());

        if (StrUtil.isBlank(docRequest.getUntil())) {
            docRequest.setUntil(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        }

        String taskId = orchestrator.submitGeneration(docRequest);

        TaskResponse taskResponse = new TaskResponse(taskId, "pending", "5s");
        return ResponseEntity.ok(ApiResponse.success("文档生成任务已提交", taskResponse));
    }

    

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询任务状态", description = "根据任务ID查询文档生成任务状态")
    public ResponseEntity<ApiResponse<TaskStatusResponse>> getTaskStatus(
            @PathVariable String taskId) {

        GenerationLog logEntry = orchestrator.getTaskLog(taskId);
        if (logEntry == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error("任务不存在: " + taskId));
        }

        TaskStatusResponse statusResponse = new TaskStatusResponse();
        statusResponse.setTaskId(logEntry.getTaskId());
        statusResponse.setStatus(logEntry.getStatus());
        statusResponse.setCreatedAt(logEntry.getCreatedAt());
        statusResponse.setCompletedAt(logEntry.getCompletedAt());
        statusResponse.setFormat(logEntry.getFormat());
        statusResponse.setOutputPath(logEntry.getOutputPath());
        statusResponse.setErrorMessage(logEntry.getErrorMessage());

        if ("completed".equals(logEntry.getStatus())) {
            statusResponse.setProgress(100);
        } else if ("running".equals(logEntry.getStatus())) {
            statusResponse.setProgress(50);
        } else {
            statusResponse.setProgress(0);
        }

        return ResponseEntity.ok(ApiResponse.success(statusResponse));
    }

    @GetMapping("/tasks/{taskId}/result")
    @Operation(summary = "获取生成结果", description = "获取已生成的文档内容")
    public ResponseEntity<?> getTaskResult(
            @PathVariable String taskId) {

        GenerationLog logEntry = orchestrator.getTaskLog(taskId);
        if (logEntry == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error("任务不存在: " + taskId));
        }

        if (!"completed".equals(logEntry.getStatus())) {
            return ResponseEntity.status(409)
                    .body(ApiResponse.error("任务尚未完成，当前状态: " + logEntry.getStatus()));
        }

        String outputPath = logEntry.getOutputPath();
        if (StrUtil.isBlank(outputPath)) {
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("文档内容为空，未找到输出文件"));
        }

        try {
            String filePath = outputPath.startsWith("/output/") ? outputPath.substring(8) : outputPath;
            File outputFile = new File("output", filePath);
            if (!outputFile.exists()) {
                outputFile = new File(filePath);
            }
            if (!outputFile.exists()) {
                return ResponseEntity.status(500)
                        .body(ApiResponse.error("输出文件不存在: " + outputPath));
            }

            MediaType mediaType;
            if ("html".equals(logEntry.getFormat())) {
                mediaType = MediaType.TEXT_HTML;
            } else {
                mediaType = MediaType.valueOf("text/markdown");
            }

            String content = new String(Files.readAllBytes(outputFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"CHANGELOG_" + logEntry.getTaskId() + "\"")
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("读取输出文件失败: " + e.getMessage()));
        }
    }

    @GetMapping("/tasks/{taskId}/download")
    @Operation(summary = "下载生成文件", description = "以文件流方式下载已生成的文档文件")
    public ResponseEntity<?> downloadTaskFile(
            @PathVariable String taskId) {

        GenerationLog logEntry = orchestrator.getTaskLog(taskId);
        if (logEntry == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error("任务不存在: " + taskId));
        }

        if (!"completed".equals(logEntry.getStatus())) {
            return ResponseEntity.status(409)
                    .body(ApiResponse.error("任务尚未完成，当前状态: " + logEntry.getStatus()));
        }

        return downloadFileResponse(logEntry);
    }

    private static String normalizeBranch(String branch) {
        if (StrUtil.isBlank(branch)) {
            return null;
        }
        String result = branch.trim();
        // 先移除首尾斜杠
        result = StrUtil.removePrefix(result, "/");
        result = StrUtil.removeSuffix(result, "/");
        // 再移除常见的远程前缀
        if (result.startsWith("remotes/origin/")) {
            result = result.substring("remotes/origin/".length());
        } else if (result.startsWith("origin/")) {
            result = result.substring("origin/".length());
        }
        return StrUtil.isBlank(result) ? null : result;
    }

    private ResponseEntity<?> downloadFileResponse(GenerationLog logEntry) {
        String outputPath = logEntry.getOutputPath();
        if (StrUtil.isBlank(outputPath)) {
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("文档内容为空，未找到输出文件"));
        }

        try {
            String filePath = outputPath.startsWith("/output/") ? outputPath.substring(8) : outputPath;
            File outputFile = new File("output", filePath);
            if (!outputFile.exists()) {
                outputFile = new File(filePath);
            }
            if (!outputFile.exists()) {
                return ResponseEntity.status(500)
                        .body(ApiResponse.error("输出文件不存在: " + outputPath));
            }

            MediaType mediaType;
            if ("html".equals(logEntry.getFormat())) {
                mediaType = MediaType.TEXT_HTML;
            } else {
                mediaType = MediaType.valueOf("text/markdown");
            }

            String extension = filePath.contains(".") ? filePath.substring(filePath.lastIndexOf(".")) : "";
            InputStreamResource resource = new InputStreamResource(new FileInputStream(outputFile));

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(outputFile.length())
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"CHANGELOG_" + logEntry.getTaskId() + extension + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("下载文件失败: " + e.getMessage()));
        }
    }

}
