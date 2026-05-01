package com.devops.ai.web.controller;

import com.devops.ai.core.template.Template;
import com.devops.ai.core.template.TemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

@Controller
@RequestMapping("/templates")
public class TemplateManagerController {

    private static final Logger log = LoggerFactory.getLogger(TemplateManagerController.class);

    private final TemplateService templateService;

    public TemplateManagerController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public String listTemplates(Model model) {
        model.addAttribute("templates", templateService.getAllTemplates());
        return "template-manager";
    }

    @GetMapping("/{name}")
    @ResponseBody
    public ResponseEntity<Template> getTemplate(@PathVariable String name) {
        Template template = templateService.getTemplate(name);
        if (template == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(template);
    }

    @PostMapping("/preview")
    @ResponseBody
    public ResponseEntity<Map<String, String>> previewTemplate(@RequestParam String content) {
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("projectName", "示例项目");
            context.put("projectVersion", "v1.0.0");
            context.put("branch", "main");
            context.put("buildTime", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            context.put("dateRange", "2026-04-01 - 2026-04-30");
            context.put("totalCommits", 25);
            context.put("totalAuthors", 5);

            String categories = "### 新功能\n- 新增用户登录功能（作者：张三）\n- 添加数据导出功能（作者：李四）\n\n### Bug修复\n- 修复页面崩溃问题（作者：王五）\n";
            context.put("categories", categories);

            Template t = new Template("preview", "预览", "", content, false);
            String result = t.render(context);
            Map<String, String> resp = new LinkedHashMap<>();
            resp.put("content", result);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Template preview failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/save")
    public String saveTemplate(@ModelAttribute Template template, RedirectAttributes redirectAttributes) {
        try {
            templateService.saveTemplate(template);
            log.info("Template saved: {}", template.getName());
            redirectAttributes.addFlashAttribute("message", "模板保存成功");
        } catch (Exception e) {
            log.error("Failed to save template '{}': {}", template.getName(), e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "模板保存失败: " + e.getMessage());
        }
        return "redirect:/templates";
    }

    @PostMapping("/delete")
    public String deleteTemplate(@RequestParam String name, RedirectAttributes redirectAttributes) {
        try {
            templateService.deleteTemplate(name);
            log.info("Template deleted: {}", name);
            redirectAttributes.addFlashAttribute("message", "模板删除成功");
        } catch (IllegalArgumentException e) {
            log.warn("Cannot delete built-in template '{}': {}", name, e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete template '{}': {}", name, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "模板删除失败: " + e.getMessage());
        }
        return "redirect:/templates";
    }

    @PostMapping("/reset/{name}")
    public String resetTemplate(@PathVariable String name, RedirectAttributes redirectAttributes) {
        try {
            templateService.resetTemplate(name);
            log.info("Built-in template '{}' reset to original", name);
            redirectAttributes.addFlashAttribute("message", "内置模板已恢复为系统默认");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to reset template '{}': {}", name, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "模板恢复失败: " + e.getMessage());
        }
        return "redirect:/templates";
    }
}
