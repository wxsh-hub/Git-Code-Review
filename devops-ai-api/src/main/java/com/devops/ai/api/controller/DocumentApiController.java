package com.devops.ai.api.controller;

import com.devops.ai.api.dto.*;
import com.devops.ai.core.generator.DocumentRequest;
import com.devops.ai.core.generator.DocumentResult;
import com.devops.ai.core.generator.GenerationOrchestrator;
import com.devops.ai.infrastructure.entity.GenerationLog;
import com.devops.ai.infrastructure.repository.GenerationLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cn.hutool.core.util.StrUtil;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "文档生成", description = "文档生成相关接口")
public class DocumentApiController {

    private final GenerationOrchestrator orchestrator;
    private final GenerationLogRepository generationLogRepository;

    public DocumentApiController(GenerationOrchestrator orchestrator,
                                 GenerationLogRepository generationLogRepository) {
        this.orchestrator = orchestrator;
        this.generationLogRepository = generationLogRepository;
    }

    @PostMapping("/generate")
    @Operation(summary = "触发文档生成", description = "提交文档生成任务，异步执行")
    public ResponseEntity<ApiResponse<TaskResponse>> generate(
            @Valid @RequestBody GenerateRequest request) {

        boolean hasTimeParams = StrUtil.isNotBlank(request.getSince()) || StrUtil.isNotBlank(request.getUntil());
        boolean hasHashParams = StrUtil.isNotBlank(request.getSinceHash()) || StrUtil.isNotBlank(request.getUntilHash());
        if (hasTimeParams && hasHashParams) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("时间范围和Hash范围不能同时使用，请选择其中一种筛选方式"));
        }

        DocumentRequest docRequest = new DocumentRequest();
        docRequest.setProjectId(request.getProjectId());
        docRequest.setBranch(request.getBranch());
        docRequest.setTemplateName(request.getTemplateName());
        docRequest.setFormat(request.getFormat());
        docRequest.setSince(request.getSince());
        docRequest.setUntil(request.getUntil());
        docRequest.setAuthor(request.getAuthor());
        docRequest.setSinceHash(request.getSinceHash());
        docRequest.setUntilHash(request.getUntilHash());
        docRequest.setDimensions(request.getDimensions());
        docRequest.setIncremental(request.isIncremental());
        docRequest.setUseAiClassifier(request.isUseAiClassifier());

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
            @PathVariable String taskId,
            @RequestHeader(value = "Accept", defaultValue = "text/markdown") String acceptType) {

        GenerationLog logEntry = orchestrator.getTaskLog(taskId);
        if (logEntry == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error("任务不存在: " + taskId));
        }

        if (!"completed".equals(logEntry.getStatus())) {
            return ResponseEntity.status(409)
                    .body(ApiResponse.error("任务尚未完成，当前状态: " + logEntry.getStatus()));
        }

        DocumentRequest request = new DocumentRequest();
        request.setProjectId(logEntry.getProjectId());
        request.setFormat(logEntry.getFormat());

        DocumentResult result = orchestrator.generateSync(request);

        if (result.isSuccess() && result.getContent() != null) {
            MediaType mediaType;
            if ("html".equals(logEntry.getFormat())) {
                mediaType = MediaType.TEXT_HTML;
            } else if ("pdf".equals(logEntry.getFormat())) {
                mediaType = MediaType.APPLICATION_PDF;
            } else {
                mediaType = MediaType.valueOf("text/markdown");
            }

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"CHANGELOG_" + logEntry.getTaskId() + "\"")
                    .body(result.getContent());
        }

        return ResponseEntity.ok(ApiResponse.error("文档内容为空"));
    }
}
