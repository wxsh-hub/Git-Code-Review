package com.devops.ai.api.controller;

import com.devops.ai.api.dto.ApiResponse;
import com.devops.ai.core.template.Template;
import com.devops.ai.core.template.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/templates")
@Tag(name = "模板管理", description = "文档模板管理接口")
public class TemplateApiController {

    private final TemplateService templateService;

    public TemplateApiController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    @Operation(summary = "获取所有模板")
    public ResponseEntity<ApiResponse<List<Template>>> getTemplates() {
        return ResponseEntity.ok(ApiResponse.success(templateService.getAllTemplates()));
    }

    @PostMapping
    @Operation(summary = "保存自定义模板")
    public ResponseEntity<ApiResponse<Template>> saveTemplate(@RequestBody Template template) {
        templateService.saveTemplate(template);
        return ResponseEntity.ok(ApiResponse.success("模板已保存", template));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除模板")
    public ResponseEntity<ApiResponse<Void>> deleteTemplate(@PathVariable String name) {
        templateService.deleteTemplate(name);
        return ResponseEntity.ok(ApiResponse.success("模板已删除", null));
    }

    @PostMapping("/render")
    @Operation(summary = "渲染模板预览")
    public ResponseEntity<ApiResponse<String>> renderTemplate(@RequestBody Map<String, Object> request) {
        String templateName = (String) request.get("templateName");
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) request.get("context");
        String rendered = templateService.renderTemplate(templateName, context);
        return ResponseEntity.ok(ApiResponse.success(rendered));
    }
}
