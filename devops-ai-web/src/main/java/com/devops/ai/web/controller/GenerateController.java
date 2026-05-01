package com.devops.ai.web.controller;

import com.devops.ai.core.generator.DocumentRequest;
import com.devops.ai.core.generator.GenerationOrchestrator;
import com.devops.ai.core.gitlab.AuthManager;
import com.devops.ai.core.gitlab.GitCloneService;
import com.devops.ai.core.gitlab.GitLabService;
import com.devops.ai.core.model.AuthorInfo;
import com.devops.ai.core.model.Branch;
import com.devops.ai.core.model.ProjectInfo;
import com.devops.ai.core.template.TemplateService;
import com.devops.ai.infrastructure.entity.GitLabConfig;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.repository.GitLabConfigRepository;
import com.devops.ai.infrastructure.repository.GenerationLogRepository;
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

    private final GitLabConfigRepository gitLabConfigRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final GitLabService gitLabService;
    private final GenerationOrchestrator orchestrator;
    private final TemplateService templateService;
    private final AuthManager authManager;
    private final GitCloneService gitCloneService;

    public GenerateController(GitLabConfigRepository gitLabConfigRepository,
                              ProjectConfigRepository projectConfigRepository,
                              GitLabService gitLabService,
                              GenerationOrchestrator orchestrator,
                              TemplateService templateService,
                              AuthManager authManager,
                              GitCloneService gitCloneService) {
        this.gitLabConfigRepository = gitLabConfigRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.gitLabService = gitLabService;
        this.orchestrator = orchestrator;
        this.templateService = templateService;
        this.authManager = authManager;
        this.gitCloneService = gitCloneService;
    }

    @GetMapping
    public String generatePage(Model model) {
        List<GitLabConfig> configs = gitLabConfigRepository.findByActiveTrue();

        model.addAttribute("configs", configs);
        model.addAttribute("templates", templateService.getAllTemplates());
        model.addAttribute("formats", new String[]{"markdown", "html"});
        model.addAttribute("dimensions", new String[]{"author", "time", "project"});

        return "generate";
    }

    @GetMapping("/projects")
    @ResponseBody
    public List<Map<String, Object>> getProjects(@RequestParam Long configId) {
        try {
            List<ProjectConfig> dbProjects = projectConfigRepository.findByGitlabConfigId(configId);
            if (!dbProjects.isEmpty()) {
                return dbProjects.stream().map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("projectId", p.getProjectId());
                    m.put("projectName", p.getProjectName());
                    return m;
                }).collect(Collectors.toList());
            }

            GitLabConfig config = gitLabConfigRepository.findById(configId).orElse(null);
            if (config == null) {
                return Collections.emptyList();
            }

            if ("clone".equals(config.getConnectMode())) {
                ProjectInfo info = gitCloneService.getProjectInfo(config);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("projectId", info.getName());
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

        } catch (Exception e) {
            log.error("Failed to fetch projects for config {}: {}", configId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @GetMapping("/branches")
    @ResponseBody
    public List<Branch> getBranches(@RequestParam String projectId, @RequestParam Long configId) {
        try {
            GitLabConfig config = gitLabConfigRepository.findById(configId).orElse(null);
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
            GitLabConfig config = gitLabConfigRepository.findById(configId).orElse(null);
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
            GitLabConfig config = gitLabConfigRepository.findById(configId).orElse(null);
            if (config == null) {
                redirectAttributes.addFlashAttribute("error", "GitLab配置不存在");
                return "redirect:/generate";
            }

            boolean hasTimeParams = StrUtil.isNotBlank(request.getSince()) || StrUtil.isNotBlank(request.getUntil());
            boolean hasHashParams = StrUtil.isNotBlank(request.getSinceHash()) || StrUtil.isNotBlank(request.getUntilHash());
            if (hasTimeParams && hasHashParams) {
                redirectAttributes.addFlashAttribute("error", "时间范围和Hash范围不能同时使用，请选择其中一种筛选方式");
                return "redirect:/generate";
            }

            if (request.getProjectName() == null || request.getProjectName().isEmpty()) {
                if ("clone".equals(config.getConnectMode())) {
                    ProjectInfo info = gitCloneService.getProjectInfo(config);
                    request.setProjectName(info.getName());
                } else {
                    List<ProjectConfig> dbProjects = projectConfigRepository.findByGitlabConfigId(configId);
                    if (!dbProjects.isEmpty()) {
                        for (ProjectConfig p : dbProjects) {
                            if (p.getProjectId().equals(request.getProjectId())) {
                                request.setProjectName(p.getProjectName());
                                break;
                            }
                        }
                    }
                    if (request.getProjectName() == null) {
                        request.setProjectName(request.getProjectId());
                    }
                }
            }

            if (StrUtil.isBlank(request.getUntil())) {
                request.setUntil(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            }

            List<GitLabConfig> allConfigs = gitLabConfigRepository.findAll();
            for (GitLabConfig c : allConfigs) {
                boolean isSelected = c.getId().equals(configId);
                if (c.getActive() != isSelected) {
                    c.setActive(isSelected);
                    gitLabConfigRepository.save(c);
                }
            }
            log.info("Switched active GitLab config to id={}", configId);

            String taskId = orchestrator.submitGeneration(request);
            log.info("Document generation task submitted: {}", taskId);
            return "redirect:/history?taskId=" + taskId;
        } catch (Exception e) {
            log.error("Failed to submit generation task: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "文档生成任务提交失败: " + e.getMessage());
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
