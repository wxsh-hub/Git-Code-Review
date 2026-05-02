package com.devops.ai.api.controller;

import com.devops.ai.api.dto.ApiResponse;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.repository.ProjectConfigRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "项目管理", description = "项目查询接口，供第三方对接获取项目编码（projectCode）")
public class ProjectApiController {

    private final ProjectConfigRepository projectConfigRepository;

    public ProjectApiController(ProjectConfigRepository projectConfigRepository) {
        this.projectConfigRepository = projectConfigRepository;
    }

    @GetMapping
    @Operation(summary = "获取项目列表", description = "获取所有已配置的项目列表")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listProjects() {
        List<ProjectConfig> projects = projectConfigRepository.findByActiveTrue();
        List<Map<String, Object>> result = projects.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectCode", p.getProjectCode());
            m.put("name", p.getName());
            m.put("projectId", p.getProjectId());
            m.put("gitlabUrl", p.getGitlabUrl());
            m.put("defaultBranch", p.getDefaultBranch());
            m.put("templateName", p.getTemplateName());
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{projectCode}")
    @Operation(summary = "获取项目详情", description = "根据项目编码获取项目详细信息")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProject(
            @Parameter(description = "项目编码", required = true) @PathVariable String projectCode) {
        ProjectConfig project = projectConfigRepository.findByProjectCode(projectCode);
        if (project == null) {
            return ResponseEntity.status(404).body(ApiResponse.error("Project not found: " + projectCode));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectCode", project.getProjectCode());
        m.put("name", project.getName());
        m.put("projectId", project.getProjectId());
        m.put("gitlabUrl", project.getGitlabUrl());
        m.put("defaultBranch", project.getDefaultBranch());
        m.put("templateName", project.getTemplateName());
        return ResponseEntity.ok(ApiResponse.success(m));
    }
}
