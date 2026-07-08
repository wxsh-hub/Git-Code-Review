package com.devops.ai.core.review.ai;

import com.devops.ai.core.review.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 对代码审查发现的 P0/P1/P2 漏洞定位责任人。
 *
 * <p>策略：对每条 finding 的行号范围，用 git log -L 精确查找最后一次修改
 * 这些行的提交者。失败了就回退到 git log -1 -- file。比起 JGit blame
 * （依赖 clone 历史完整性），native git log 直接用远端历史，不受 squash 影响。</p>
 *
 * <p>P3-P4 级别的 Finding 不处理（直接透传）。</p>
 */
@Component
public class FindingBlameTracer {

    private static final Logger log = LoggerFactory.getLogger(FindingBlameTracer.class);

    /**
     * 对 P0/P1/P2 Finding 列表执行责任定位。
     */
    public List<Finding> trace(List<Finding> findings, String repoPath) {
        if (findings == null || findings.isEmpty()) return Collections.emptyList();
        if (repoPath == null || repoPath.isEmpty()) {
            log.warn("repoPath is null, skipping blame tracing for {} findings", findings.size());
            return findings;
        }

        File gitDir = new File(repoPath, ".git");
        if (!gitDir.exists()) {
            log.warn("No .git directory at {}, skipping blame tracing for {} findings", repoPath, findings.size());
            return findings;
        }

        int traced = 0;
        int skipped = 0;

        for (Finding f : findings) {
            if (!isHighSeverity(f.getSeverity())) {
                skipped++;
                continue;
            }

            String handler = findLastCommitter(repoPath, f.getFile(), f.getStartLine(), f.getEndLine());
            if (handler != null && !handler.isEmpty()) {
                f.setCandidateHandler(handler);
                f.setOwner(handler);
                traced++;
            } else {
                skipped++;
            }
        }

        log.info("Blame tracing complete: {} traced, {} skipped/fallback (P3-P4), {} total",
                traced, skipped, findings.size());
        return findings;
    }

    /**
     * 用 git log -L 定位最后修改了指定行范围的提交者。
     * 失败时降级到 git log -1 -- file。
     */
    private String findLastCommitter(String repoPath, String filePath, int startLine, int endLine) {
        if (filePath == null || filePath.isEmpty()) return null;

        // 先尝试行级精确定位：git log -1 --format="%an" -L start,end:file
        if (startLine > 0 && endLine >= startLine) {
            String range = startLine + "," + endLine + ":" + filePath;
            String result = gitLog(repoPath, "-1", "--format=%an", "-L", range);
            if (result != null && !result.isEmpty()) {
                log.debug("Line-level blame: {} L{}-{} → {}", filePath, startLine, endLine, result);
                return result;
            }
        }

        // 降级：git log -1 --format="%an" -- file（文件最后一次修改者）
        String result = gitLog(repoPath, "-1", "--format=%an", "--follow", "--", filePath);
        if (result != null && !result.isEmpty()) {
            log.debug("File-level fallback: {} → {}", filePath, result);
            return result;
        }

        // 最终降级：git log -1 --format="%an"（不管文件，仓库最近作者）
        String anyResult = gitLog(repoPath, "-1", "--format=%an");
        if (anyResult != null && !anyResult.isEmpty()) {
            log.debug("Repo-level fallback: {} → {}", filePath, anyResult);
            return anyResult;
        }

        return null;
    }

    /**
     * 执行 git log 命令，仅返回第一行（作者名）。
     * 因为 -L 会在作者名后面附带完整 diff，这里只取第一行即可。
     * 仅在退出码为 0 时返回结果，否则返回 null 并将错误输出记入日志。
     */
    private String gitLog(String repoPath, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("log");
            cmd.addAll(Arrays.asList(args));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(repoPath));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String firstLine = null;
            StringBuilder errOut = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (firstLine == null) {
                        firstLine = line.trim();
                    } else {
                        if (errOut.length() > 0) errOut.append(" ");
                        errOut.append(line);
                    }
                }
            }
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String full = (firstLine != null ? firstLine + " " : "") + errOut.toString();
                log.warn("git log failed (exit={}) in repo {}: args={}, output={}",
                        exitCode, repoPath, Arrays.asList(args),
                        full.length() > 200 ? full.substring(0, 200) + "..." : full);
                return null;
            }

            return (firstLine != null && !firstLine.isEmpty()) ? firstLine : null;
        } catch (Exception e) {
            log.warn("git log execution failed for {}: {}", repoPath, e.getMessage());
            return null;
        }
    }

    private boolean isHighSeverity(FindingSeverity s) {
        return s == FindingSeverity.BLOCKER || s == FindingSeverity.HIGH || s == FindingSeverity.MEDIUM;
    }
}
