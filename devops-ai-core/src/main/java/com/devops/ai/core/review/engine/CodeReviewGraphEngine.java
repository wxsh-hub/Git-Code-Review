package com.devops.ai.core.review.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Component
public class CodeReviewGraphEngine {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewGraphEngine.class);

    /**
     * Run code-review-graph static analysis on the given repository.
     * Builds/updates the graph, then detects changes against the given base commit.
     *
     * @param repoPath absolute path to the git repository
     * @param sinceHash base commit hash for change detection
     * @return raw JSON output from detect-changes
     */
    public String analyze(String repoPath, String sinceHash) {
        String buildOutput = runProcess("code-review-graph", "build", "--repo", repoPath, "--skip-postprocess");
        log.debug("code-review-graph build output: {}", buildOutput != null ? buildOutput.trim() : "null");

        String detectOutput = runProcess("code-review-graph", "detect-changes",
                "--base", sinceHash, "--repo", repoPath);
        String json = extractJson(detectOutput);
        log.info("code-review-graph detect-changes complete: {} chars", json != null ? json.length() : 0);
        return json;
    }

    private String runProcess(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("code-review-graph exited with code {}: {}", exitCode, output);
            }
            return output;
        } catch (Exception e) {
            log.warn("code-review-graph execution failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract JSON object from the CLI output (skip leading non-JSON lines like warnings/errors).
     */
    private String extractJson(String output) {
        if (output == null || output.isEmpty()) return null;
        int start = output.indexOf('{');
        if (start < 0) return null;
        return output.substring(start);
    }
}
