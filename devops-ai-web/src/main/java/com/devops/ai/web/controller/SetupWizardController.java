package com.devops.ai.web.controller;

import com.devops.ai.core.gitlab.GitLabService;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.repository.ProjectConfigRepository;
import com.devops.ai.infrastructure.util.ConfigEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/setup")
public class SetupWizardController {

    private static final Logger log = LoggerFactory.getLogger(SetupWizardController.class);

    private final ProjectConfigRepository projectConfigRepository;
    private final GitLabService gitLabService;
    private final ConfigEncryptor configEncryptor;

    public SetupWizardController(ProjectConfigRepository projectConfigRepository,
                                 GitLabService gitLabService,
                                 ConfigEncryptor configEncryptor) {
        this.projectConfigRepository = projectConfigRepository;
        this.gitLabService = gitLabService;
        this.configEncryptor = configEncryptor;
    }

    @GetMapping
    public String setup(Model model) {
        return "setup-wizard";
    }

    @PostMapping("/gitlab")
    public String saveProjectConfig(@RequestParam String name,
                                   @RequestParam String gitlabUrl,
                                   @RequestParam String authType,
                                   @RequestParam(required = false) String token,
                                   @RequestParam(required = false) String username,
                                   @RequestParam(required = false) String password,
                                   RedirectAttributes redirectAttributes) {
        try {
            if (name == null || name.trim().isEmpty()
                    || gitlabUrl == null || gitlabUrl.trim().isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Name and GitLab URL are required");
                return "redirect:/setup";
            }

            ProjectConfig config = new ProjectConfig();
            config.setName(name.trim());
            config.setGitlabUrl(gitlabUrl.trim());
            config.setAuthType(authType);
            config.setApiVersion("v4");
            config.setActive(true);

            String rawCredentials;
            if ("token".equals(authType)) {
                if (token == null || token.trim().isEmpty()) {
                    redirectAttributes.addFlashAttribute("error", "Token is required for token auth");
                    return "redirect:/setup";
                }
                rawCredentials = token.trim();
                config.setConnectMode("api");
            } else {
                String user = username != null ? username.trim() : "";
                String pass = password != null ? password : "";
                if (user.isEmpty() || pass.isEmpty()) {
                    redirectAttributes.addFlashAttribute("error", "Username and password are required");
                    return "redirect:/setup";
                }
                rawCredentials = user + ":" + pass;
                config.setConnectMode("clone");
            }
            config.setCredentials(configEncryptor.encrypt(rawCredentials));

            projectConfigRepository.save(config);
            log.info("Project saved: {} at {}, authType={}, connectMode={}",
                    name, gitlabUrl, authType, config.getConnectMode());

            try {
                boolean connected = gitLabService.testConnection(config);
                if (connected) {
                    redirectAttributes.addFlashAttribute("message", "GitLab connection successful! Project saved.");
                }
            } catch (Exception connEx) {
                log.warn("GitLab connection test failed after save: {}", connEx.getMessage());
                redirectAttributes.addFlashAttribute("warning",
                        "Project saved but unable to connect to GitLab: "
                        + connEx.getMessage() + ". You can modify later.");
            }

            return "redirect:/config/project";

        } catch (Exception e) {
            log.error("Failed to save project config: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "Failed to save: " + e.getMessage());
            return "redirect:/setup";
        }
    }
}
