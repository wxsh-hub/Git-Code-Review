package com.devops.ai.core.efficiency.detector;

import com.devops.ai.core.efficiency.model.DiffHunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 unified diff 格式，将每个 hunk 提取为结构化的 DiffHunk 对象。
 * 提取行号范围和前后文，供重复修改检测和 AI 分类使用。
 */
public class DiffHunkParser {

    private static final Logger log = LoggerFactory.getLogger(DiffHunkParser.class);

    // @@ -oldStart[,oldCount] +newStart[,newCount] @@
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@");

    // Binary file marker
    private static final Pattern BINARY_FILE = Pattern.compile(
            "^Binary files .+ and .+ differ", Pattern.CASE_INSENSITIVE);

    // File header patterns
    private static final Pattern DIFF_GIT = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern OLD_FILE = Pattern.compile("^--- (?:a/(.+)|\\S+)$");
    private static final Pattern NEW_FILE = Pattern.compile("^\\+\\+\\+ (?:b/(.+)|\\S+)$");

    /**
     * 解析 unified diff 文本，提取所有 hunk。
     * 如果 diff 是二进制文件或无法解析，返回空列表。
     */
    public List<DiffHunk> parse(String diffText, String filePath) {
        List<DiffHunk> hunks = new ArrayList<>();
        if (diffText == null || diffText.isEmpty()) {
            return hunks;
        }

        if (BINARY_FILE.matcher(diffText).find()) {
            log.debug("Skipping binary file diff: {}", filePath);
            return hunks;
        }

        String[] lines = diffText.split("\n");
        DiffHunk current = null;
        StringBuilder hunkLines = null;
        String extractedFilePath = filePath;

        for (String line : lines) {
            // Try to extract file path from diff headers
            Matcher dm = DIFF_GIT.matcher(line);
            if (dm.find() && extractedFilePath == null) {
                extractedFilePath = dm.group(2);
            }

            Matcher hm = HUNK_HEADER.matcher(line);
            if (hm.find()) {
                // Finish previous hunk
                if (current != null && hunkLines != null) {
                    current.setRawHunk(hunkLines.toString());
                    processContext(current);
                    hunks.add(current);
                }

                current = new DiffHunk();
                current.setFilePath(extractedFilePath);
                int oldStart = Integer.parseInt(hm.group(1));
                int oldCount = hm.group(2) != null ? Integer.parseInt(hm.group(2)) : 1;
                int newStart = Integer.parseInt(hm.group(3));
                int newCount = hm.group(4) != null ? Integer.parseInt(hm.group(4)) : 1;
                current.setOldStart(oldStart);
                current.setOldCount(oldCount);
                current.setNewStart(newStart);
                current.setNewCount(newCount);

                hunkLines = new StringBuilder();
                hunkLines.append(line).append("\n");
            } else if (current != null && hunkLines != null) {
                hunkLines.append(line).append("\n");
            }
        }

        // Finish last hunk
        if (current != null && hunkLines != null) {
            current.setRawHunk(hunkLines.toString());
            processContext(current);
            hunks.add(current);
        }

        return hunks;
    }

    /**
     * 从 hunk 原始内容中分离出：上下文字段（contextBefore, contextAfter）
     * 和实际变更内容（changeContent）。
     */
    private void processContext(DiffHunk hunk) {
        String raw = hunk.getRawHunk();
        if (raw == null) return;

        String[] lines = raw.split("\n");
        StringBuilder contextBefore = new StringBuilder();
        StringBuilder changeContent = new StringBuilder();
        StringBuilder contextAfter = new StringBuilder();

        boolean inChange = false;
        boolean changeSeen = false;
        // 最多保留前后各 CONTEXT_LINES 行上下文
        final int CTX = 5;
        List<String> preBuffer = new ArrayList<>();

        for (String line : lines) {
            // Skip hunk header line
            if (line.startsWith("@@")) continue;

            if (line.startsWith("+") || line.startsWith("-")) {
                if (!inChange) {
                    // Entering change block: flush pre-buffer
                    inChange = true;
                    changeSeen = true;
                    // Only keep last CTX lines from pre-buffer
                    int start = Math.max(0, preBuffer.size() - CTX);
                    for (int i = start; i < preBuffer.size(); i++) {
                        contextBefore.append(preBuffer.get(i)).append("\n");
                    }
                    preBuffer.clear();
                }
                changeContent.append(line).append("\n");
            } else {
                // Context or metadata line
                String cleanLine = line.startsWith(" ") ? line.substring(1) : line;
                if (inChange) {
                    // Exiting change block
                    if (changeSeen && contextAfter.toString().split("\n").length < CTX) {
                        contextAfter.append(cleanLine).append("\n");
                    }
                } else {
                    preBuffer.add(cleanLine);
                }
            }
        }

        hunk.setContextBefore(contextBefore.toString().trim());
        hunk.setChangeContent(changeContent.toString().trim());
        hunk.setContextAfter(contextAfter.toString().trim());
    }

    /**
     * 从文件路径提取文件名（用于日志）
     */
    public static String extractFilePath(String diffText) {
        if (diffText == null) return "unknown";
        Matcher dm = DIFF_GIT.matcher(diffText);
        if (dm.find()) return dm.group(2);
        return "unknown";
    }
}
