package com.devops.ai.core.review.ai;

import com.devops.ai.core.review.model.GrepRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Grep 搜索和文件读取服务 — 迭代式深度扫描的核心组件。
 *
 * <p>当 LLM 在审查过程中请求查看外部代码时，此类负责：
 * <ul>
 *   <li>有 filePath → 读取该文件，提取相关方法体（含注解和 JavaDoc）</li>
 *   <li>无 filePath → grep -rn 全局搜索</li>
 * </ul>
 *
 * <h3>readFile 方法提取策略</h3>
 * <ol>
 *   <li>逐行扫描，正则匹配方法签名</li>
 *   <li>往上回溯收集注解和 JavaDoc</li>
 *   <li>往下花括号计数提取方法体（跳过字符串/字符/注释中的括号）</li>
 *   <li>重载方法全部返回</li>
 *   <li>接口/抽象方法（分号结尾）直接返回签名</li>
 * </ol>
 */
@Component
public class GrepTracer {

    private static final Logger log = LoggerFactory.getLogger(GrepTracer.class);

    /** grep 最大返回行数 */
    private static final int MAX_LINES = 30;

    /** grep -C 上下文行数 */
    private static final int CONTEXT_LINES = 5;

    /** 方法体最大字符数（超长截断） */
    private static final int MAX_METHOD_CHARS = 5000;

    // ================================================================
    // 公开接口
    // ================================================================

    /**
     * 执行单个符号的搜索。
     *
     * @param repoPath 仓库根路径
     * @param symbol   符号（如 UserRepository.findById）
     * @param filePath 可选文件路径（有则 readFile，无则 grep）
     * @return 格式化的代码片段
     */
    public String search(String repoPath, String symbol, String filePath) {
        if (filePath != null && !filePath.isEmpty()) {
            return readFile(repoPath, filePath, symbol);
        }
        return grepSearch(repoPath, symbol);
    }

    /**
     * 批量执行 grep 请求，拼接格式化结果。
     *
     * @param repoPath 仓库根路径
     * @param requests grep 请求列表
     * @return 格式化的搜索结果（可直接拼入 prompt）
     */
    public String executeRequests(String repoPath, List<GrepRequest> requests) {
        log.info("GrepTracer: executing {} grep requests", requests.size());
        StringBuilder sb = new StringBuilder();
        for (GrepRequest req : requests) {
            String mode = (req.getFile() != null && !req.getFile().isEmpty()) ? "readFile" : "grep";
            log.info("GrepTracer: [{}] symbol='{}', file='{}', reason='{}'",
                    mode, req.getSymbol(), req.getFile(), req.getReason());
            sb.append("### ").append(req.getSymbol());
            if (req.getReason() != null && !req.getReason().isEmpty()) {
                sb.append(" — ").append(req.getReason());
            }
            sb.append("\n```java\n");
            String result = search(repoPath, req.getSymbol(), req.getFile());
            sb.append(result);
            sb.append("\n```\n\n");
            log.info("GrepTracer: [{}] '{}' returned {} chars", mode, req.getSymbol(), result.length());
        }
        return sb.toString();
    }

    // ================================================================
    // readFile：读取文件，提取方法体
    // ================================================================

    /**
     * 读取指定文件，提取与 symbol 相关的方法体（含注解和 JavaDoc）。
     *
     * @param repoPath 仓库根路径
     * @param filePath 文件路径（相对于仓库根目录）
     * @param symbol   符号（如 UserRepository.findById）
     * @return 提取的方法体代码，或错误提示
     */
    private String readFile(String repoPath, String filePath, String symbol) {
        Path fullPath = Paths.get(repoPath, filePath);
        if (!Files.exists(fullPath)) {
            log.warn("GrepTracer: file not found: {} (symbol={})", fullPath, symbol);
            return "(文件不存在: " + filePath + ")";
        }

        try {
            List<String> lines = Files.readAllLines(fullPath, StandardCharsets.UTF_8);
            log.debug("GrepTracer: readFile {} ({} lines), searching for '{}'", filePath, lines.size(), symbol);
            if (lines.isEmpty()) return "(文件为空)";

            // 提取方法名（从 symbol 中解析）
            String methodName = symbol.contains(".")
                    ? symbol.substring(symbol.lastIndexOf('.') + 1)
                    : symbol;

            // 构建方法签名正则
            // 匹配：(修饰符) 返回类型 方法名(
            Pattern methodPattern = Pattern.compile(
                    "\\b(public|private|protected|static|final|abstract|synchronized|native|default)\\b"
                            + ".*\\b" + Pattern.quote(methodName) + "\\s*\\(");

            // 也匹配构造方法（无返回类型，直接方法名开头）
            Pattern constructorPattern = Pattern.compile(
                    "\\b(public|private|protected)\\s+" + Pattern.quote(methodName) + "\\s*\\(");

            // 找到所有匹配的方法签名行
            List<Integer> methodStartLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (methodPattern.matcher(line).find() || constructorPattern.matcher(line).find()) {
                    methodStartLines.add(i);
                }
            }

            if (methodStartLines.isEmpty()) {
                log.debug("GrepTracer: method '{}' not found in {}, falling back to grep", symbol, filePath);
                return grepSearch(repoPath, symbol);
            }

            // 对每个匹配的方法，提取完整方法体
            log.debug("GrepTracer: found {} overloaded version(s) of '{}' in {}",
                    methodStartLines.size(), symbol, filePath);
            StringBuilder result = new StringBuilder();
            for (int startLine : methodStartLines) {
                String extracted = extractMethod(lines, startLine);
                if (result.length() > 0) result.append("\n");
                result.append(extracted);
            }

            log.debug("GrepTracer: extracted '{}' from {}, total {} chars",
                    symbol, filePath, result.length());
            return result.toString();

        } catch (Exception e) {
            log.warn("GrepTracer: failed to read {}: {}", filePath, e.getMessage());
            return "(读取文件失败: " + e.getMessage() + ")";
        }
    }

    /**
     * 从指定行开始提取完整方法（含注解和 JavaDoc）。
     */
    private String extractMethod(List<String> lines, int startLine) {
        // 1. 往上回溯，收集注解和 JavaDoc
        int blockStart = startLine;
        for (int i = startLine - 1; i >= 0; i--) {
            String line = lines.get(i).trim();
            if (line.startsWith("@") || line.startsWith("/*") || line.startsWith("*")
                    || line.startsWith("//") || line.isEmpty()) {
                blockStart = i;
            } else {
                break;
            }
        }

        // 2. 检查是否是接口/抽象方法（分号结尾）
        String signatureLine = lines.get(startLine);
        if (signatureLine.trim().endsWith(";")) {
            StringBuilder sb = new StringBuilder();
            for (int i = blockStart; i <= startLine; i++) {
                sb.append(lines.get(i)).append("\n");
            }
            sb.append("// (接口/抽象方法，无实现体)");
            return sb.toString();
        }

        // 3. 从签名行开始，花括号计数提取方法体
        int braceCount = 0;
        boolean started = false;
        StringBuilder methodBody = new StringBuilder();

        for (int i = startLine; i < lines.size(); i++) {
            String line = lines.get(i);
            methodBody.append(line).append("\n");

            braceCount += countBraces(line);
            if (!started && braceCount > 0) started = true;
            if (started && braceCount == 0) break;

            // 截断保护
            if (methodBody.length() > MAX_METHOD_CHARS) {
                methodBody.append("... [truncated, total ").append(methodBody.length()).append(" chars]\n");
                break;
            }
        }

        // 4. 拼接：注解/JavaDoc + 方法体
        StringBuilder sb = new StringBuilder();
        for (int i = blockStart; i < startLine; i++) {
            sb.append(lines.get(i)).append("\n");
        }
        sb.append(methodBody);
        return sb.toString();
    }

    // ================================================================
    // 花括号计数（跳过字符串/字符/注释中的括号）
    // ================================================================

    /**
     * 统计一行中有效花括号数量。
     * 跳过：字符串字面量、字符字面量、单行注释、多行注释。
     */
    private int countBraces(String line) {
        int count = 0;
        boolean inString = false;
        boolean inChar = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            // 单行注释 //（不在字符串/字符内时）
            if (!inString && !inChar && i + 1 < line.length()
                    && c == '/' && line.charAt(i + 1) == '/') {
                break; // 后面都是注释，跳出
            }

            // 字符串字面量 "..."（不在字符内时）
            if (c == '"' && !inChar) {
                if (!isEscaped(line, i)) {
                    inString = !inString;
                }
                continue;
            }

            // 字符字面量 '...'（不在字符串内时）
            if (c == '\'' && !inString) {
                if (!isEscaped(line, i)) {
                    inChar = !inChar;
                }
                continue;
            }

            // 跳过字符串/字符内的内容
            if (inString || inChar) continue;

            if (c == '{') count++;
            else if (c == '}') count--;
        }
        return count;
    }

    /**
     * 判断位置 i 的字符是否被转义（前面有奇数个连续反斜杠）。
     * 例如 "\\"" → 第二个 " 未被转义（\\是转义的反斜杠）
     *      "\"" → 第二个 " 被转义
     */
    private boolean isEscaped(String line, int i) {
        int backslashes = 0;
        for (int j = i - 1; j >= 0 && line.charAt(j) == '\\'; j--) {
            backslashes++;
        }
        return backslashes % 2 != 0; // 奇数个反斜杠 = 被转义
    }

    // ================================================================
    // grep 搜索
    // ================================================================

    /**
     * 用 grep -rn 搜索符号。
     */
    private String grepSearch(String repoPath, String symbol) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "grep", "-rn", "-C", String.valueOf(CONTEXT_LINES),
                    "--include=*.java",
                    "-m", String.valueOf(MAX_LINES),
                    "--", symbol, ".");
            pb.directory(new File(repoPath));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder outputBuilder = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuilder.append(line).append("\n");
                }
            }
            String output = outputBuilder.toString().trim();
            int exitCode = p.waitFor();
            if (exitCode != 0 || output.isEmpty()) {
                log.debug("GrepTracer: grep '{}' returned no results (exit={})", symbol, exitCode);
                return "(未找到匹配结果)";
            }
            int lineCount = output.split("\n").length;
            log.debug("GrepTracer: grep '{}' returned {} lines, {} chars", symbol, lineCount, output.length());
            return output;
        } catch (Exception e) {
            log.warn("GrepTracer: grep '{}' failed: {}", symbol, e.getMessage());
            return "(grep 执行失败: " + e.getMessage() + ")";
        }
    }
}
