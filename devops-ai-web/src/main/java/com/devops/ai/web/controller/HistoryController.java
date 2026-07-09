package com.devops.ai.web.controller;

import com.devops.ai.infrastructure.entity.GenerationLog;
import com.devops.ai.infrastructure.repository.GenerationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/history")
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    private final GenerationLogRepository generationLogRepository;

    public HistoryController(GenerationLogRepository generationLogRepository) {
        this.generationLogRepository = generationLogRepository;
    }

    @GetMapping
    public String history(@RequestParam(required = false) String taskId, Model model) {
        if (taskId != null && !taskId.isEmpty()) {
            GenerationLog log = generationLogRepository.findByTaskId(taskId);
            model.addAttribute("currentTask", log);
        }

        List<GenerationLog> logs = generationLogRepository.findAll(
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        model.addAttribute("logs", logs);

        return "history";
    }

    @PostMapping("/delete/{taskId}")
    public String deleteHistory(@PathVariable String taskId, RedirectAttributes redirectAttributes) {
        try {
            GenerationLog logEntry = generationLogRepository.findByTaskId(taskId);
            if (logEntry != null) {
                String extension = "html".equals(logEntry.getFormat()) ? "html" : "md";
                java.io.File file = new java.io.File("output", taskId + "." + extension);
                if (file.exists() && file.delete()) {
                    log.info("Deleted output file: {}", file.getAbsolutePath());
                }
                if (Boolean.TRUE.equals(logEntry.getHasReview()) && logEntry.getReviewOutputPath() != null) {
                    String reviewPath = logEntry.getReviewOutputPath();
                    String reviewFilePath = reviewPath.startsWith("/output/") ? reviewPath.substring(8) : reviewPath;
                    java.io.File reviewFile = new java.io.File("output", reviewFilePath);
                    if (reviewFile.exists() && reviewFile.delete()) {
                        log.info("Deleted review file: {}", reviewFile.getAbsolutePath());
                    }
                }
                generationLogRepository.delete(logEntry);
                log.info("Deleted history record: {}", taskId);
                redirectAttributes.addFlashAttribute("message", "历史记录已删除");
            } else {
                redirectAttributes.addFlashAttribute("error", "记录不存在");
            }
        } catch (Exception e) {
            log.error("Failed to delete history {}: {}", taskId, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "删除失败: " + e.getMessage());
        }
        return "redirect:/history";
    }

    @GetMapping("/view/{taskId}")
    public String viewResult(@PathVariable String taskId) {
        GenerationLog logEntry = generationLogRepository.findByTaskId(taskId);
        if (logEntry == null || !"completed".equals(logEntry.getStatus()) || logEntry.getOutputPath() == null) {
            return "redirect:/history?taskId=" + taskId;
        }
        return "redirect:" + logEntry.getOutputPath();
    }

    @GetMapping("/review/view/{taskId}")
    public String viewReview(@PathVariable String taskId) {
        GenerationLog logEntry = generationLogRepository.findByTaskId(taskId);
        if (logEntry == null || !Boolean.TRUE.equals(logEntry.getHasReview()) || logEntry.getReviewOutputPath() == null) {
            return "redirect:/history?taskId=" + taskId;
        }
        return "redirect:" + logEntry.getReviewOutputPath();
    }

    @GetMapping("/review/download/{taskId}")
    public ResponseEntity<byte[]> downloadReview(@PathVariable String taskId) {
        GenerationLog logEntry = generationLogRepository.findByTaskId(taskId);
        if (logEntry == null || !Boolean.TRUE.equals(logEntry.getHasReview()) || logEntry.getReviewOutputPath() == null) {
            return ResponseEntity.notFound().build();
        }

        String outputPath = logEntry.getReviewOutputPath();
        String filePath = outputPath.startsWith("/output/") ? outputPath.substring(8) : outputPath;
        java.io.File file = new java.io.File("output", filePath);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
            String extension = filePath.endsWith(".html") ? "html" : "md";
            MediaType mediaType = "html".equals(extension)
                    ? MediaType.TEXT_HTML : MediaType.valueOf("text/markdown");
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"REVIEW_" + taskId + "." + extension + "\"")
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/download/{taskId}")
    public ResponseEntity<byte[]> downloadResult(@PathVariable String taskId) {
        return downloadMergedReport(taskId);
    }

    @GetMapping("/download/merged/{taskId}")
    public ResponseEntity<byte[]> downloadMerged(@PathVariable String taskId) {
        return downloadMergedReport(taskId);
    }

    private ResponseEntity<byte[]> downloadMergedReport(String taskId) {
        GenerationLog logEntry = generationLogRepository.findByTaskId(taskId);
        if (logEntry == null || !"completed".equals(logEntry.getStatus()) || logEntry.getOutputPath() == null) {
            return ResponseEntity.notFound().build();
        }

        String extension = "html".equals(logEntry.getFormat()) ? "html" : "md";
        java.io.File file = new java.io.File("output", taskId + "." + extension);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
            MediaType mediaType = "html".equals(logEntry.getFormat())
                    ? MediaType.TEXT_HTML : MediaType.valueOf("text/markdown");
            String filename = "整合__" + taskId + "." + extension;
            String encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/download/summary/{taskId}")
    public ResponseEntity<byte[]> downloadSummary(@PathVariable String taskId) {
        return downloadSplitReport(taskId, "_summary", "摘要");
    }

    @GetMapping("/download/disposition/{taskId}")
    public ResponseEntity<byte[]> downloadDisposition(@PathVariable String taskId) {
        return downloadSplitReport(taskId, "_disposition", "问题");
    }

    @GetMapping("/download/module/{taskId}")
    public ResponseEntity<byte[]> downloadModule(@PathVariable String taskId) {
        return downloadSplitReport(taskId, "_module", "模块");
    }

    @GetMapping("/download/appendix/{taskId}")
    public ResponseEntity<byte[]> downloadAppendix(@PathVariable String taskId) {
        return downloadSplitReport(taskId, "_appendix", "效率");
    }

    private ResponseEntity<byte[]> downloadSplitReport(String taskId, String suffix, String reportName) {
        GenerationLog logEntry = generationLogRepository.findByTaskId(taskId);
        if (logEntry == null || !"completed".equals(logEntry.getStatus())) {
            return ResponseEntity.notFound().build();
        }

        String extension = "html".equals(logEntry.getFormat()) ? "html" : "md";
        java.io.File file = new java.io.File("output", taskId + suffix + "." + extension);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
            MediaType mediaType = "html".equals(logEntry.getFormat())
                    ? MediaType.TEXT_HTML : MediaType.valueOf("text/markdown");
            String filename = reportName + "__" + taskId + "." + extension;
            String encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
