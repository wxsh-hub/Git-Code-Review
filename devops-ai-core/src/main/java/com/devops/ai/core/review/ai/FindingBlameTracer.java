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
 * <p>策略：
 * <ol>
 *   <li>按文件分组 Finding</li>
 *   <li>每个文件跑一次 {@code git blame --porcelain <file>}，解析每行的作者</li>
 *   <li>对每条 Finding 查找其行号范围对应的作者（取起始行的 blame 作者）</li>
 *   <li>失败回退到 {@code git log -1 -- <file>}</li>
 * </ol>
 *
 * <p>相比旧版逐条 {@code git log -L}（N 条 Finding = N 次进程启动），
 * 新版按文件合并 blame（M 个文件 = M 次进程），大幅减少进程开销。</p>
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
        if (findings == null || findings.isEmpty()) return findings;
        if (repoPath == null || repoPath.isEmpty()) {
            log.warn("repoPath is null, skipping blame tracing for {} findings", findings.size());
            return findings;
        }

        File gitDir = new File(repoPath, ".git");
        if (!gitDir.exists()) {
            log.warn("No .git directory at {}, skipping blame tracing for {} findings", repoPath, findings.size());
            return findings;
        }

        // ---- 按文件分组 Finding（只看 P0/P1/P2）----
        Map<String, List<Finding>> byFile = new LinkedHashMap<>();
        for (Finding f : findings) {
            if (!isHighSeverity(f.getSeverity())) continue;
            String file = f.getFile();
            if (file == null || file.isEmpty()) continue;
            byFile.computeIfAbsent(file, k -> new ArrayList<>()).add(f);
        }

        int traced = 0;
        int skipped = 0;

        // ---- 每个文件跑一次 git blame ----
        for (Map.Entry<String, List<Finding>> entry : byFile.entrySet()) {
            String filePath = entry.getKey();
            List<Finding> fileFindings = entry.getValue();

            Map<Integer, String> lineAuthors = blameFile(repoPath, filePath);

            for (Finding f : fileFindings) {
                String handler = null;
                if (!lineAuthors.isEmpty()) {
                    // 取起始行的 blame 作者
                    handler = lineAuthors.get(f.getStartLine());
                    if (handler == null && f.getEndLine() > f.getStartLine()) {
                        // 起始行没命中，取范围内任一命中行
                        for (int l = f.getStartLine() + 1; l <= f.getEndLine(); l++) {
                            handler = lineAuthors.get(l);
                            if (handler != null) break;
                        }
                    }
                }

                // 文件级 fallback
                if (handler == null) {
                    handler = gitLog(repoPath, "-1", "--format=%an", "--", filePath);
                }

                if (handler != null && !handler.isEmpty()) {
                    f.setCandidateHandler(handler);
                    f.setOwner(handler);
                    traced++;
                } else {
                    skipped++;
                }
            }
        }

        log.info("Blame tracing complete: {} traced, {} skipped ({} files), {} total",
                traced, skipped, byFile.size(), findings.size());
        return findings;
    }

    /**
     * 对单个文件跑 git blame --porcelain，解析出 行号→作者 映射。
     *
     * <p>porcelain 格式示例：
     * <pre>
     * a1b2c3d4 1 5 3    ← hash, origLine(1), finalLine(5), numLines(3)
     * author Alice
     * ...(更多 metadata)
     * \tcode line        ← 代码行（\t 开头）
     * </pre>
     * 上面的例子中，finalLine=5, numLines=3，意味着行 5-7 都是 Alice 写的。
     */
    Map<Integer, String> blameFile(String repoPath, String filePath) {
        Map<Integer, String> result = new LinkedHashMap<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "blame", "--porcelain", "--", filePath);
            pb.directory(new File(repoPath));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            int currentLine = -1;
            int currentNumLines = 1;
            String currentAuthor = null;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("\t")) {
                        // 代码行：将当前 author 绑定到 currentLine 开始的 numLines 行
                        if (currentLine > 0 && currentAuthor != null) {
                            for (int i = 0; i < currentNumLines; i++) {
                                result.put(currentLine + i, currentAuthor);
                            }
                        }
                        currentLine = -1;
                        currentAuthor = null;
                        currentNumLines = 1;
                    } else if (line.startsWith("author ")) {
                        currentAuthor = line.substring(7).trim();
                    } else if (line.matches("^[0-9a-f]{40} \\d+ \\d+ \\d+$")) {
                        // hash 行：hash origLine finalLine numLines
                        String[] parts = line.split(" ");
                        if (parts.length >= 4) {
                            try {
                                currentLine = Integer.parseInt(parts[2]);
                                currentNumLines = Integer.parseInt(parts[3]);
                            } catch (NumberFormatException ignored) { }
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.debug("git blame failed (exit={}) for {} in {}", exitCode, filePath, repoPath);
                return Collections.emptyMap();
            }
        } catch (Exception e) {
            log.debug("git blame execution failed for {} in {}: {}", filePath, repoPath, e.getMessage());
            return Collections.emptyMap();
        }
        return result;
    }

    /**
     * git log 回退：找不到 blame 时取文件最后修改者。
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
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (firstLine == null) {
                        firstLine = line.trim();
                    }
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.debug("git log fallback failed (exit={}) for {}", exitCode, Arrays.asList(args));
                return null;
            }
            return (firstLine != null && !firstLine.isEmpty()) ? firstLine : null;
        } catch (Exception e) {
            log.debug("git log fallback execution failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean isHighSeverity(FindingSeverity s) {
        return s == FindingSeverity.BLOCKER || s == FindingSeverity.HIGH || s == FindingSeverity.MEDIUM;
    }
}
