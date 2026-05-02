package com.devops.ai.web.controller;

import com.devops.ai.core.generator.DocumentRequest;
import com.devops.ai.core.generator.GenerationOrchestrator;
import com.devops.ai.core.gitlab.AuthManager;
import com.devops.ai.core.gitlab.GitCloneService;
import com.devops.ai.core.model.AuthorInfo;
import com.devops.ai.core.model.Branch;
import com.devops.ai.core.template.TemplateService;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.repository.ProjectConfigRepository;
import cn.hutool.core.util.StrUtil;
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
@RequestMapping("/generate")
public class GenerateController {

    private static final Logger log = LoggerFactory.getLogger(GenerateController.class);

    private final ProjectConfigRepository projectConfigRepository;
    private final GenerationOrchestrator orchestrator;
    private final TemplateService templateService;
    private final AuthManager authManager;
    private final GitCloneService gitCloneService;

    public GenerateController(ProjectConfigRepository projectConfigRepository,
                              GenerationOrchestrator orchestrator,
                              TemplateService templateService,
                              AuthManager authManager,
                              GitCloneService gitCloneService) {
        this.projectConfigRepository = projectConfigRepository;
        this.orchestrator = orchestrator;
        this.templateService = templateService;
        this.authManager = authManager;
        this.gitCloneService = gitCloneService;
    }

    @GetMapping
    public String generatePage(Model model) {
        List<ProjectConfig> projects = projectConfigRepository.findByActiveTrue();

        model.addAttribute("projects", projects);
        model.addAttribute("templates", templateService.getAllTemplates());
        model.addAttribute("formats", new String[]{"markdown", "html"});
        model.addAttribute("dimensions", new String[]{"author", "time", "project"});

        return "generate";
    }

    @GetMapping("/project-detail/{id}")
    @ResponseBody
    public Map<String, Object> getProjectDetail(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ProjectConfig config = projectConfigRepository.findById(id).orElse(null);
            if (config == null) {
                result.put("error", "Project config not found");
                return result;
            }
            result.put("id", config.getId());
            result.put("name", config.getName());
            result.put("projectId", config.getProjectId());
            result.put("defaultBranch", config.getDefaultBranch());
            result.put("templateName", config.getTemplateName() != null ? config.getTemplateName() : "standard");
            result.put("gitlabUrl", config.getGitlabUrl());
            result.put("connectMode", config.getConnectMode());
        } catch (Exception e) {
            log.error("Failed to get project detail: {}", e.getMessage(), e);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/branches")
    @ResponseBody
    public List<Branch> getBranches(@RequestParam String projectId, @RequestParam Long configId) {
        try {
            ProjectConfig config = projectConfigRepository.findById(configId).orElse(null);
            if (config == null) {
                return Collections.emptyList();
            }

            if ("clone".equals(config.getConnectMode())) {
                return gitCloneService.getBranches(config);
            }

            GitLabApi api = authManager.createApi(config);
            List<org.gitlab4j.api.models.Branch> gitlabBranches = api.getRepositoryApi().getBranches(projectId);
            return gitlabBranches.stream().map(this::convertToBranch).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to fetch branches for project {}: {}", projectId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @GetMapping("/authors")
    @ResponseBody
    public List<AuthorInfo> getAuthors(@RequestParam Long configId,
                                       @RequestParam(required = false) String projectId) {
        try {
            ProjectConfig config = projectConfigRepository.findById(configId).orElse(null);
            if (config == null) {
                return Collections.emptyList();
            }

            if ("clone".equals(config.getConnectMode())) {
                return gitCloneService.getAuthors(config);
            }

            GitLabApi api = authManager.createApi(config);
            List<org.gitlab4j.api.models.Commit> commits = api.getCommitsApi().getCommits(projectId, 100, 1);

            Set<String> seen = new HashSet<>();
            List<AuthorInfo> result = new ArrayList<>();
            for (org.gitlab4j.api.models.Commit c : commits) {
                String name = c.getAuthorName();
                String email = c.getAuthorEmail();
                if (name == null) continue;
                String key = name + " <" + (email != null ? email : "") + ">";
                if (seen.add(key)) {
                    result.add(new AuthorInfo(name, email));
                }
            }
            result.sort(Comparator.comparing(AuthorInfo::getName));
            return result;

        } catch (Exception e) {
            log.error("Failed to fetch authors: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @PostMapping
    public String submitGeneration(@RequestParam Long configId,
                                   @ModelAttribute DocumentRequest request,
                                   RedirectAttributes redirectAttributes) {
        try {
            ProjectConfig project = projectConfigRepository.findById(configId).orElse(null);
            if (project == null) {
                redirectAttributes.addFlashAttribute("error", "Project config not found");
                return "redirect:/generate";
            }

            boolean hasTimeParams = StrUtil.isNotBlank(request.getSince()) || StrUtil.isNotBlank(request.getUntil());
            boolean hasHashParams = StrUtil.isNotBlank(request.getSinceHash()) || StrUtil.isNotBlank(request.getUntilHash());
            if (hasTimeParams && hasHashParams) {
                redirectAttributes.addFlashAttribute("error", "Time range and hash range cannot be used together");
                return "redirect:/generate";
            }

            request.setProjectId(project.getProjectId());
            if (request.getProjectName() == null || request.getProjectName().isEmpty()) {
                request.setProjectName(project.getName());
            }
            if (StrUtil.isBlank(request.getBranch())) {
                request.setBranch(project.getDefaultBranch());
            }

            if (StrUtil.isBlank(request.getUntil())) {
                request.setUntil(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            }

            String taskId = orchestrator.submitGeneration(request);
            log.info("Document generation task submitted: {}", taskId);
            return "redirect:/history?taskId=" + taskId;
        } catch (Exception e) {
            log.error("Failed to submit generation task: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "Document generation failed: " + e.getMessage());
            return "redirect:/generate";
        }
    }

    private Branch convertToBranch(org.gitlab4j.api.models.Branch gitlabBranch) {
        Branch branch = new Branch();
        branch.setName(gitlabBranch.getName());
        if (gitlabBranch.getCommit() != null) {
            branch.setCommitId(gitlabBranch.getCommit().getId());
        }
        Boolean merged = gitlabBranch.getMerged();
        branch.setMerged(merged != null && merged);
        return branch;
    }
}
