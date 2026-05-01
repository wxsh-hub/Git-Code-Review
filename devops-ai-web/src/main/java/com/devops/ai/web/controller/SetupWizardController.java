package com.devops.ai.web.controller;

import com.devops.ai.core.gitlab.GitLabService;
import com.devops.ai.infrastructure.entity.GitLabConfig;
import com.devops.ai.infrastructure.repository.GitLabConfigRepository;
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

@Controller
@RequestMapping("/setup")
public class SetupWizardController {

    private static final Logger log = LoggerFactory.getLogger(SetupWizardController.class);

    private final GitLabConfigRepository gitLabConfigRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final GitLabService gitLabService;
    private final ConfigEncryptor configEncryptor;

    public SetupWizardController(GitLabConfigRepository gitLabConfigRepository,
                                 ProjectConfigRepository projectConfigRepository,
                                 GitLabService gitLabService,
                                 ConfigEncryptor configEncryptor) {
        this.gitLabConfigRepository = gitLabConfigRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.gitLabService = gitLabService;
        this.configEncryptor = configEncryptor;
    }

    @GetMapping
    public String setup(Model model) {
        return "setup-wizard";
    }

    @PostMapping("/gitlab")
    public String saveGitLabConfig(@RequestParam String name,
                                   @RequestParam String gitlabUrl,
                                   @RequestParam String authType,
                                   @RequestParam(required = false) String token,
                                   @RequestParam(required = false) String username,
                                   @RequestParam(required = false) String password,
                                   RedirectAttributes redirectAttributes) {
        try {
            if (name == null || name.trim().isEmpty()
                    || gitlabUrl == null || gitlabUrl.trim().isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "配置名称和GitLab地址不能为空");
                return "redirect:/setup";
            }

            if ("token".equals(authType) && (token == null || token.trim().isEmpty())) {
                redirectAttributes.addFlashAttribute("error", "Token 认证模式下 Token 不能为空");
                return "redirect:/setup";
            }

            if ("password".equals(authType)) {
                String user = username != null ? username.trim() : "";
                String pass = password != null ? password : "";
                if (user.isEmpty() || pass.isEmpty()) {
                    redirectAttributes.addFlashAttribute("error", "用户名和密码不能为空");
                    return "redirect:/setup";
                }
            }

            GitLabConfig config = new GitLabConfig();
            config.setName(name.trim());
            config.setGitlabUrl(gitlabUrl.trim());
            config.setAuthType(authType);
            config.setApiVersion("v4");
            config.setActive(true);

            String rawCredentials;
            if ("token".equals(authType)) {
                rawCredentials = token.trim();
                config.setConnectMode("api");
            } else {
                rawCredentials = (username != null ? username.trim() : "") + ":" + password;
                config.setConnectMode("clone");
            }
            config.setCredentials(configEncryptor.encrypt(rawCredentials));

            gitLabConfigRepository.save(config);
            log.info("GitLab configuration saved: {} at {}, authType={}, connectMode={}",
                    name, gitlabUrl, authType, config.getConnectMode());

            try {
                boolean connected = gitLabService.testConnection(config);
                if (connected) {
                    redirectAttributes.addFlashAttribute("message", "GitLab 连接成功！配置已保存。");
                }
            } catch (Exception connEx) {
                log.warn("GitLab connection test failed after save: {}", connEx.getMessage());
                redirectAttributes.addFlashAttribute("warning",
                        "配置已保存，但无法连接到 GitLab: " + connEx.getMessage() + "。可稍后在配置页修改。");
            }

            return "redirect:/generate";

        } catch (Exception e) {
            log.error("Failed to save GitLab config: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "配置保存失败: " + e.getMessage());
            return "redirect:/setup";
        }
    }
}
