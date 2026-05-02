package com.devops.ai.web.controller;

import com.devops.ai.core.gitlab.AuthManager;
import com.devops.ai.core.gitlab.GitCloneService;
import com.devops.ai.core.model.Branch;
import com.devops.ai.core.model.ProjectInfo;
import com.devops.ai.core.template.TemplateService;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.repository.ProjectConfigRepository;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import org.gitlab4j.api.GitLabApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/config/project")
public class ProjectConfigController {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfigController.class);

    private final ProjectConfigRepository projectConfigRepository;
    private final TemplateService templateService;
    private final ConfigEncryptor configEncryptor;
    private final AuthManager authManager;
    private final GitCloneService gitCloneService;

    public ProjectConfigController(ProjectConfigRepository projectConfigRepository,
                                   TemplateService templateService,
                                   ConfigEncryptor configEncryptor,
                                   AuthManager authManager,
                                   GitCloneService gitCloneService) {
        this.projectConfigRepository = projectConfigRepository;
        this.templateService = templateService;
        this.configEncryptor = configEncryptor;
        this.authManager = authManager;
        this.gitCloneService = gitCloneService;
    }

    @GetMapping
    public String projectConfig(Model model) {
        List<ProjectConfig> projects = projectConfigRepository.findAll();
        model.addAttribute("projects", projects);
        model.addAttribute("templates", templateService.getAllTemplates());
        return "project-config";
    }

    @PostMapping
    public String saveProject(@ModelAttribute ProjectConfig project,
                              @RequestParam(required = false) String projectCode,
                              @RequestParam(required = false) String token,
                              @RequestParam(required = false) String username,
                              @RequestParam(required = false) String password,
                              RedirectAttributes redirectAttributes) {
        try {
            if (projectCode != null && !projectCode.trim().isEmpty()) {
                project.setProjectCode(projectCode.trim());
            }

            if (project.getCredentials() != null && !project.getCredentials().isEmpty()) {
                project.setCredentials(configEncryptor.encrypt(project.getCredentials()));
            } else if ("token".equals(project.getAuthType()) && token != null && !token.isEmpty()) {
                project.setCredentials(configEncryptor.encrypt(token.trim()));
                project.setConnectMode("api");
            } else if ("password".equals(project.getAuthType()) && username != null && password != null) {
                String raw = (username.trim()) + ":" + password;
                project.setCredentials(configEncryptor.encrypt(raw));
                project.setConnectMode("clone");
            }

            project.setApiVersion("v4");
            project.setActive(true);
            projectConfigRepository.save(project);
            log.info("Project saved: name={}, code={}", project.getName(), project.getProjectCode());
            redirectAttributes.addFlashAttribute("message", "项目已保存，编码: " + project.getProjectCode());
        } catch (Exception e) {
            log.error("Failed to save project: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "保存失败: " + e.getMessage());
        }
        return "redirect:/config/project";
    }

    @PostMapping("/delete")
    public String deleteProject(@RequestParam Long id, RedirectAttributes redirectAttributes) {
        try {
            projectConfigRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("message", "项目已删除");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "删除失败: " + e.getMessage());
        }
        return "redirect:/config/project";
    }

    @PostMapping("/test-connection")
    @ResponseBody
    public Map<String, Object> testConnection(@RequestParam String gitlabUrl,
                                              @RequestParam String authType,
                                              @RequestParam(required = false) String token,
                                              @RequestParam(required = false) String username,
                                              @RequestParam(required = false) String password) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ProjectConfig temp = new ProjectConfig();
            temp.setGitlabUrl(gitlabUrl);
            temp.setAuthType(authType);

            if ("token".equals(authType)) {
                temp.setCredentials(token != null ? token.trim() : "");
                temp.setConnectMode("api");
            } else {
                String raw = (username != null ? username.trim() : "") + ":" + (password != null ? password : "");
                temp.setCredentials(raw);
                temp.setConnectMode("clone");
            }

            boolean connected = authManager.testConnection(temp);
            if (connected) {
                result.put("success", true);
                result.put("message", "连接成功");

                List<Map<String, Object>> projects = fetchProjectList(temp);
                result.put("projects", projects);
            } else {
                result.put("success", false);
                result.put("message", "连接失败");
            }
        } catch (Exception e) {
            log.warn("Connection test failed: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/branches")
    @ResponseBody
    public Map<String, Object> getBranches(@RequestParam String gitlabUrl,
                                           @RequestParam String authType,
                                           @RequestParam(required = false) String token,
                                           @RequestParam(required = false) String username,
                                           @RequestParam(required = false) String password,
                                           @RequestParam String projectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ProjectConfig temp = new ProjectConfig();
            temp.setGitlabUrl(gitlabUrl);
            temp.setAuthType(authType);

            if ("token".equals(authType)) {
                temp.setCredentials(token != null ? token.trim() : "");
                temp.setConnectMode("api");
            } else {
                String raw = (username != null ? username.trim() : "") + ":" + (password != null ? password : "");
                temp.setCredentials(raw);
                temp.setConnectMode("clone");
            }

            if ("clone".equals(temp.getConnectMode())) {
                List<Branch> branches = gitCloneService.getBranches(temp);
                List<String> names = branches.stream().map(Branch::getName).collect(Collectors.toList());
                result.put("success", true);
                result.put("branches", names);
            } else {
                GitLabApi api = authManager.createApi(temp);
                List<org.gitlab4j.api.models.Branch> gitlabBranches = api.getRepositoryApi().getBranches(projectId);
                List<String> names = gitlabBranches.stream().map(org.gitlab4j.api.models.Branch::getName).collect(Collectors.toList());
                result.put("success", true);
                result.put("branches", names);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch branches: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "获取分支失败: " + e.getMessage());
        }
        return result;
    }

    private List<Map<String, Object>> fetchProjectList(ProjectConfig config) throws Exception {
        if ("clone".equals(config.getConnectMode())) {
            ProjectInfo info = gitCloneService.getProjectInfo(config);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectId", info.getId());
            m.put("projectName", info.getName());
            return Collections.singletonList(m);
        }

        GitLabApi api = authManager.createApi(config);
        List<org.gitlab4j.api.models.Project> projects = api.getProjectApi().getProjects();
        return projects.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectId", String.valueOf(p.getId()));
            m.put("projectName", p.getName());
            return m;
        }).collect(Collectors.toList());
    }
}
