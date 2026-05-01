package com.devops.ai.web.controller;

import com.devops.ai.infrastructure.entity.GenerationLog;
import com.devops.ai.infrastructure.repository.GitLabConfigRepository;
import com.devops.ai.infrastructure.repository.GenerationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Collections;
import java.util.List;

@Controller
public class IndexController {

    private static final Logger log = LoggerFactory.getLogger(IndexController.class);

    private final GitLabConfigRepository gitLabConfigRepository;
    private final GenerationLogRepository generationLogRepository;

    public IndexController(GitLabConfigRepository gitLabConfigRepository,
                           GenerationLogRepository generationLogRepository) {
        this.gitLabConfigRepository = gitLabConfigRepository;
        this.generationLogRepository = generationLogRepository;
    }

    @GetMapping("/")
    public String index(Model model) {
        try {
            long configCount = gitLabConfigRepository.count();
            if (configCount == 0) {
                return "redirect:/setup";
            }

            Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
            List<GenerationLog> recentLogs = generationLogRepository.findAll(
                    PageRequest.of(0, 10, sort)).getContent();

            model.addAttribute("configCount", configCount);
            model.addAttribute("recentLogs", recentLogs);

            return "index";
        } catch (Exception e) {
            log.error("Failed to load index page: {}", e.getMessage(), e);
            model.addAttribute("configCount", 0);
            model.addAttribute("recentLogs", Collections.emptyList());
            return "index";
        }
    }
}
