package com.devops.ai.core.review.ai;

import com.devops.ai.core.crg.CrgClient;
import com.devops.ai.core.crg.CrgModels;
import com.devops.ai.core.review.model.GrepRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;

/**
 * Grep 搜索和文件读取服务 — 迭代式深度扫描的核心组件。
 *
 * <p>当 LLM 在审查过程中请求查看外部代码时，此类负责：
 * <ul>
 *   <li>有 filePath → 读取该文件，提取相关方法体（含注解和 JavaDoc）</li>
 *   <li>无 filePath → grep -rn 全局搜索</li>
 * </ul>
 *
 * <p>Phase 3 CRG 增强 — 优先走 CRG 图查询（callers_of / callees_of），
 * 精确返回调用链 + 方法体，比 shell grep 准确得多。CRG 不可用时自动 fallback。
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

    /** W3 修复 + 4.2 — 并发 grep 信号量，上限可配置（默认 8） */
    @Value("${ocr.deep-scan.grep-concurrency:8}")
    private int grepConcurrency;
    private Semaphore grepSemaphore;  // @PostConstruct 中初始化

    /** Phase 3 — CRG 客户端（可选注入，CRG 不可用时为 null） */
    @Autowired(required = false)
    private CrgClient crgClient;

    @PostConstruct
    private void initSemaphore() {
        this.grepSemaphore = new Semaphore(grepConcurrency);
        log.info("[GrepTracer] grep semaphore initialized with concurrency={}", grepConcurrency);
    }

    // ================================================================
    // 公开接口
    // ================================================================

    /**
     * 执行单个符号的搜索。
     *
     * <p>Phase 3 — 优先走 CRG 图查询（callers_of / callees_of），
     * 精确返回调用链。CRG 不可用或符号无法解析时 fallback 到原逻辑。
     *
     * @param repoPath 仓库根路径
     * @param symbol   符号（如 UserRepository.findById）
     * @param filePath 可选文件路径（有则 readFile，无则 grep）
     * @return 格式化的代码片段
     */
    public String search(String repoPath, String symbol, String filePath) {
        // === Phase 3: 优先走 CRG ===
        if (crgClient != null && crgClient.isEnabled()) {
            long t0 = System.currentTimeMillis();
            String crgResult = searchViaCrg(repoPath, symbol, filePath);
            long elapsed = System.currentTimeMillis() - t0;
            if (crgResult != null) {
                log.info("[GrepTracer CRG] '{}' -> {} chars in {}ms (via CRG)", symbol, crgResult.length(), elapsed);
                return crgResult;
            }
            log.debug("[GrepTracer CRG] '{}' no CRG result, falling back to grep ({}ms)", symbol, elapsed);
        } else {
            log.debug("[GrepTracer] CRG not enabled, using original grep for '{}'", symbol);
        }

        // === fallback 1: 有 filePath 则直接读文件 ===
        if (filePath != null && !filePath.isEmpty()) {
            String result = readFile(repoPath, filePath, symbol);
            if (result != null && !result.startsWith("(")) {
                return result;
            }
        }

        // === fallback 2: grep 全量 symbol（如 "PtmWpgjService.getBusinessTypeName"）===
        String grepResult = grepSearch(repoPath, symbol);
        if (grepResult != null && !grepResult.startsWith("(未找到")) {
            return grepResult;
        }

        // === fallback 3 (W5): grep 仅方法名（去掉类名前缀）===
        if (symbol.contains(".")) {
            String methodName = symbol.substring(symbol.lastIndexOf('.') + 1);
            if (!methodName.equals(symbol)) {  // 避免和 fallback 2 重复
                log.debug("[GrepTracer] full symbol grep failed, retrying with method name '{}'", methodName);
                return grepSearch(repoPath, methodName);
            }
        }

        return grepResult;  // fallback 2 的原始结果（"(未找到匹配结果)"）
    }

    /**
     * 批量执行 grep 请求，拼接格式化结果。
     *
     * <p>Phase 3 — 每次 search() 已优先走 CRG，此处额外为 fallback 的 grep 结果
     * 附加 CRG 调用链上下文，确保 LLM 收到最完整的调用关系。
     *
     * @param repoPath 仓库根路径
     * @param requests grep 请求列表
     * @return 格式化的搜索结果（可直接拼入 prompt）
     */
    public String executeRequests(String repoPath, List<GrepRequest> requests) {
        long t0 = System.currentTimeMillis();
        log.info("[GrepTracer] executing {} grep requests", requests.size());
        StringBuilder sb = new StringBuilder();
        for (GrepRequest req : requests) {
            String mode = (req.getFile() != null && !req.getFile().isEmpty()) ? "readFile" : "grep";
            long tReq = System.currentTimeMillis();
            log.info("[GrepTracer] [{}] symbol='{}', file='{}', reason='{}'",
                    mode, req.getSymbol(), req.getFile(), req.getReason());
            sb.append("### ").append(req.getSymbol());
            if (req.getReason() != null && !req.getReason().isEmpty()) {
                sb.append(" — ").append(req.getReason());
            }
            sb.append("\n```java\n");
            String result = search(repoPath, req.getSymbol(), req.getFile());
            sb.append(result);
            sb.append("\n```\n\n");
            long tReqElapsed = System.currentTimeMillis() - tReq;
            log.info("[GrepTracer] [{}] '{}' returned {} chars in {}ms",
                    mode, req.getSymbol(), result.length(), tReqElapsed);
        }
        long elapsed = System.currentTimeMillis() - t0;
        log.info("[GrepTracer] {} requests completed in {}ms", requests.size(), elapsed);
        return sb.toString();
    }

    // ================================================================
    // Phase 3: CRG 按需查询
    // ================================================================

    /**
     * 通过 CRG 图查询获取符号信息（W5 优化 — 利用 CRG node 的 file_path 直接读取方法体）。
     * 返回 null 表示 CRG 无结果，需 fallback。
     */
    private String searchViaCrg(String repoPath, String symbol, String filePath) {
        // W5: 解析符号为完整 CrgNode（含 file_path + line_start）
        CrgModels.CrgNode resolvedNode = resolveSymbolToCrgNode(symbol);
        if (resolvedNode == null) {
            log.debug("[GrepTracer CRG] symbol '{}' could not be resolved to CRG target", symbol);
            return null;
        }
        String resolvedTarget = resolvedNode.getQualifiedName();

        StringBuilder sb = new StringBuilder();
        boolean hasCallers = false;
        boolean hasCallees = false;

        // 1. callers_of：谁调用了这个方法
        try {
            CrgModels.CrgQueryResult callers = crgClient.queryGraph("callers_of", resolvedTarget);
            if (callers != null && callers.hasResults()) {
                hasCallers = true;
                sb.append("### 调用者\n");
                int count = 0;
                int maxCallers = 5;
                for (CrgModels.CrgNode node : callers.getResults()) {
                    if (count >= maxCallers) {
                        sb.append("- ... 等共 ").append(callers.getResults().size()).append(" 个调用者\n");
                        break;
                    }
                    String name = node.getName() != null ? node.getName()
                            : (node.getQualifiedName() != null ? node.getQualifiedName() : "?");
                    String file = node.getFile() != null ? node.getFile() : "?";
                    Integer line = node.getLineStart();
                    sb.append("- ").append(name)
                      .append(" (").append(file);
                    if (line != null) {
                        sb.append(":").append(line);
                    }
                    sb.append(")\n");
                    count++;
                }
                log.debug("[GrepTracer CRG] '{}': {} callers", symbol, count);
            } else {
                sb.append("### 调用者: (无)\n");
            }
        } catch (Exception e) {
            log.warn("[GrepTracer CRG] callers_of '{}' failed: {}", resolvedTarget, e.getMessage());
            sb.append("### 调用者: (查询失败)\n");
        }

        // 2. callees_of：这个方法调用了谁
        try {
            CrgModels.CrgQueryResult callees = crgClient.queryGraph("callees_of", resolvedTarget);
            if (callees != null && callees.hasResults()) {
                hasCallees = true;
                sb.append("\n### 被调用者\n");
                int count = 0;
                int maxCallees = 5;
                for (CrgModels.CrgNode node : callees.getResults()) {
                    if (count >= maxCallees) {
                        sb.append("- ... 等共 ").append(callees.getResults().size()).append(" 个被调用者\n");
                        break;
                    }
                    String name = node.getName() != null ? node.getName()
                            : (node.getQualifiedName() != null ? node.getQualifiedName() : "?");
                    String file = node.getFile() != null ? node.getFile() : "?";
                    Integer line = node.getLineStart();
                    sb.append("- ").append(name)
                      .append(" (").append(file);
                    if (line != null) {
                        sb.append(":").append(line);
                    }
                    sb.append(")\n");
                    count++;
                }
                log.debug("[GrepTracer CRG] '{}': {} callees", symbol, count);
            } else {
                sb.append("\n### 被调用者: (无)\n");
            }
        } catch (Exception e) {
            log.warn("[GrepTracer CRG] callees_of '{}' failed: {}", resolvedTarget, e.getMessage());
            sb.append("\n### 被调用者: (查询失败)\n");
        }

        // 3. W5: 读取方法体 — 优先用 CRG node 的 file_path（无需 LLM 提供）
        String effectiveFilePath = null;
        if (filePath != null && !filePath.isEmpty()) {
            effectiveFilePath = filePath;
        } else if (resolvedNode.getFile() != null) {
            // CRG node 有 file_path：转为相对路径
            effectiveFilePath = toRelativePath(repoPath, resolvedNode.getFile());
        }
        if (effectiveFilePath != null) {
            try {
                String body = readFile(repoPath, effectiveFilePath, symbol);
                if (body != null && !body.startsWith("(")) {
                    sb.append("\n### 方法体\n```java\n").append(body).append("\n```\n");
                }
            } catch (Exception e) {
                log.debug("[GrepTracer CRG] readFile for '{}' failed: {}", symbol, e.getMessage());
            }
        }

        log.debug("[GrepTracer CRG] '{}': resolved, callers={}, callees={}, body={}",
                symbol, hasCallers, hasCallees, effectiveFilePath != null);
        return sb.toString();
    }

    /**
     * W5 新增 — 将 CRG node 的绝对路径转为相对于 repoPath 的相对路径。
     */
    private String toRelativePath(String repoPath, String absolutePath) {
        if (absolutePath == null) return null;
        // 标准化路径分隔符
        String normalizedRepo = repoPath.replace('\\', '/');
        String normalizedPath = absolutePath.replace('\\', '/');
        if (normalizedPath.startsWith(normalizedRepo)) {
            String rel = normalizedPath.substring(normalizedRepo.length());
            while (rel.startsWith("/") || rel.startsWith("\\")) {
                rel = rel.substring(1);
            }
            return rel;
        }
        // 不是 repoPath 前缀 → 无法定位文件，返回 null 让调用方跳过 readFile
        log.debug("[GrepTracer CRG] path '{}' not under repoPath '{}', skipping method body read",
                normalizedPath, normalizedRepo);
        return null;
    }

    /**
     * 将 LLM 输出的 "ClassName.methodName" 映射到 CRG qualified_name。
     *
     * <p>W5 修复 — 多策略匹配：
     * <ol>
     *   <li>通过 CRG searchNodes 查找 name 匹配的 Function 节点</li>
     *   <li>有类名限定时：endsWith 精确匹配 > contains 模糊匹配 > 按 priority 评分</li>
     *   <li>无类名时返回第一个候选</li>
     *   <li>解析失败返回 null（调用方应 fallback 到 shell grep）</li>
     * </ol>
     *
     * <p>本方法设为 public，供 FindingVerifier 等外部调用者复用。
     *
     * @param symbol LLM 输出的符号名（如 "UserRepository.findById" 或 "findById"）
     * @return CRG qualified_name，解析失败返回 null
     */
    public String resolveSymbolToCrgTarget(String symbol) {
        CrgModels.CrgNode node = resolveSymbolToCrgNode(symbol);
        return node != null ? node.getQualifiedName() : null;
    }

    /**
     * W5 新增 — 解析符号返回完整 CrgNode（含 file_path + line_start），
     * 使 searchViaCrg 可直接读取方法体而无需 LLM 提供文件路径。
     */
    private CrgModels.CrgNode resolveSymbolToCrgNode(String symbol) {
        if (symbol == null || symbol.isEmpty()) return null;
        if (crgClient == null || !crgClient.isEnabled()) return null;

        // "UserRepository.findById" → className="UserRepository", methodName="findById"
        String className, methodName;
        if (symbol.contains(".")) {
            int lastDot = symbol.lastIndexOf('.');
            className = symbol.substring(0, lastDot);
            methodName = symbol.substring(lastDot + 1);
        } else {
            className = null;
            methodName = symbol;
        }

        // 通过 CRG searchNodes 查找
        List<CrgModels.CrgNode> candidates;
        try {
            candidates = crgClient.searchNodes(methodName, "Function", 15);
        } catch (Exception e) {
            log.debug("[GrepTracer CRG] searchNodes('{}') failed: {}", methodName, e.getMessage());
            return null;
        }

        if (candidates == null || candidates.isEmpty()) {
            log.debug("[GrepTracer CRG] no Function nodes found for '{}'", methodName);
            return null;
        }

        // 有类名限定时精确匹配（W5: 多策略）
        if (className != null) {
            String needle = className + "." + methodName;

            // 策略1：endsWith 精确匹配（qualified_name 以 "ClassName.methodName" 结尾）
            for (CrgModels.CrgNode node : candidates) {
                String qn = node.getQualifiedName();
                if (qn != null && qn.endsWith(needle)) {
                    log.debug("[GrepTracer CRG] '{}' -> '{}' (endsWith exact match)", symbol, qn);
                    return node;
                }
            }

            // 策略2：同名方法 + 文件名中包含类名（处理类名不精确的情况）
            for (CrgModels.CrgNode node : candidates) {
                String qn = node.getQualifiedName();
                if (qn != null && qn.contains("." + methodName)) {
                    // 检查文件路径或 qualified_name 中是否包含类名
                    if ((node.getFile() != null && node.getFile().contains(className))
                            || qn.contains(className)) {
                        log.debug("[GrepTracer CRG] '{}' -> '{}' (file/name contains class name)", symbol, qn);
                        return node;
                    }
                }
            }

            // 无类名匹配，返回 null 而非瞎猜—调用方 fallback 到 grep
            log.debug("[GrepTracer CRG] '{}' class '{}' not matched in {} candidates, fallback to grep",
                    symbol, className, candidates.size());
            return null;
        }

        // 无类名限定，返回第一个候选
        CrgModels.CrgNode first = candidates.get(0);
        log.debug("[GrepTracer CRG] '{}' -> '{}' (no class name, first match)", symbol, first.getQualifiedName());
        return first;
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
            log.warn("[GrepTracer] file not found: {} (symbol={})", fullPath, symbol);
            return "(文件不存在: " + filePath + ")";
        }

        try {
            List<String> lines = Files.readAllLines(fullPath, StandardCharsets.UTF_8);
            log.debug("[GrepTracer] readFile {} ({} lines), searching for '{}'", filePath, lines.size(), symbol);
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
                // W5: 方法未在该文件中找到，回退到 grep（先全量 symbol，再仅方法名）
                log.debug("[GrepTracer] method '{}' not found in {}, falling back to grep", symbol, filePath);
                String grepResult = grepSearch(repoPath, symbol);
                if (grepResult != null && !grepResult.startsWith("(未找到") && !grepResult.startsWith("(grep")) {
                    return grepResult;
                }
                // 提取方法名重试
                String methodPart = symbol.contains(".")
                        ? symbol.substring(symbol.lastIndexOf('.') + 1)
                        : symbol;
                if (!methodPart.equals(symbol)) {
                    log.debug("[GrepTracer] retrying grep with method name '{}'", methodPart);
                    return grepSearch(repoPath, methodPart);
                }
                return grepResult;
            }

            // 对每个匹配的方法，提取完整方法体
            log.debug("[GrepTracer] found {} overloaded version(s) of '{}' in {}",
                    methodStartLines.size(), symbol, filePath);
            StringBuilder result = new StringBuilder();
            for (int startLine : methodStartLines) {
                String extracted = extractMethod(lines, startLine);
                if (result.length() > 0) result.append("\n");
                result.append(extracted);
            }

            log.debug("[GrepTracer] extracted '{}' from {}, total {} chars",
                    symbol, filePath, result.length());
            return result.toString();

        } catch (Exception e) {
            log.warn("[GrepTracer] failed to read {}: {}", filePath, e.getMessage());
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
     * 用 grep -rn 搜索符号（W3 修复 — 信号量控制并发）。
     */
    private String grepSearch(String repoPath, String symbol) {
        try {
            // 4.2 — tryAcquire 5 秒超时，避免审查线程无限阻塞
            if (!grepSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                log.warn("[GrepTracer] grep '{}' semaphore timeout after 5s", symbol);
                return "(grep 并发已满，请稍后重试)";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "(grep 执行被中断)";
        }
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
                log.debug("[GrepTracer] grep '{}' returned no results (exit={})", symbol, exitCode);
                return "(未找到匹配结果)";
            }
            int lineCount = output.split("\n").length;
            log.debug("[GrepTracer] grep '{}' returned {} lines, {} chars", symbol, lineCount, output.length());
            return output;
        } catch (Exception e) {
            log.warn("[GrepTracer] grep '{}' failed: {}", symbol, e.getMessage());
            return "(grep 执行失败: " + e.getMessage() + ")";
        } finally {
            grepSemaphore.release();
        }
    }
}
