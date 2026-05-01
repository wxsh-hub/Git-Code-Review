package com.devops.ai.api.controller;

import com.devops.ai.api.dto.ApiResponse;
import com.devops.ai.infrastructure.entity.GenerationLog;
import com.devops.ai.infrastructure.repository.GenerationLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
@Tag(name = "生成历史", description = "文档生成历史查询接口")
public class HistoryApiController {

    private final GenerationLogRepository generationLogRepository;

    public HistoryApiController(GenerationLogRepository generationLogRepository) {
        this.generationLogRepository = generationLogRepository;
    }

    @GetMapping
    @Operation(summary = "获取生成历史列表")
    public ResponseEntity<ApiResponse<List<GenerationLog>>> getHistory(
            @RequestParam(required = false) String projectId) {

        List<GenerationLog> logs;
        if (projectId != null && !projectId.isEmpty()) {
            logs = generationLogRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        } else {
            logs = generationLogRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        }

        return ResponseEntity.ok(ApiResponse.success(logs));
    }
}
