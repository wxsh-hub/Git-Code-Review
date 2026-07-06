# devops-ai 代码审查能力升级方案

## 1. 问题诊断

### 1.1 现状

devops-ai 的代码审查流水线拥有合格的基础设施（code-review-graph 架构分析、JGit diff 收集、报告渲染、编排器），唯一的薄弱环节是 `CodeReviewAiService`——它的 LLM 调用方式过于原始：

| 问题 | 现状 | 影响 |
|------|------|------|
| 单次 prompt 调用 | 所有 diff + graph JSON 一次性塞入 prompt | 大变更集 token 爆炸，LLM "走捷径" 选择性跳过文件 |
| 无行号定位 | LLM 自由描述行号 | 行号漂移，定位不准确 |
| 正则解析输出 | `split("###")` + 关键词模糊匹配 | 格式偏差即解析失败，静默降级 |
| 无质量校验 | 一次调用直接当结果 | 误报和漏报无法过滤 |
| 粗暴截断 | diff 超 5000 字符直接丢弃 | 大文件变更审查形同虚设 |
| 串行单线程 | 一次一个 prompt | 大项目非常慢 |

### 1.2 方案目标

**保留** code-review-graph 的架构分析能力，**替换** CodeReviewAiService 的 LLM 调用层为 open-code-review MCP server，实现逐文件并发审查、行级精准评论、结构化 JSON 输出。

> **⚠ 注意：** 本文档专注于 OCR MCP 接入的技术细节。关于统一数据结构（Finding）、报告口径、二次过滤、Bug 归因等全局改造，详见 `代码审查与效率分析升级方案.md`。根据总方案，本文中的 `OcrComment`、`CodeReviewResult` 扩展字段最终都会收敛到统一的 `Finding`，下游模块（ReviewReportGenerator 等）统一引用 Finding 而非 OcrComment。

---

## 2. 目标架构

### 2.1 改造后数据流

```
用户/CI 发起审查（useCodeReview=true）
  │
  ├── CodeReviewDataCollector     → JGit clone + diff → List<FileDiff>
  │                                 [不改]
  ├── CodeReviewGraphEngine       → CLI code-review-graph → graph JSON
  │                                 [不改]
  │
  ├── CodeReviewAiService [改造]
  │    │
  │    ├─ 1. 提取 graph 分析结果 → background 文本
  │    │     包含: 影响范围(直接/间接影响文件)、风险等级、风险信号
  │    │
  │    ├─ 2. 提取变更文件列表 → path 参数
  │    │     从 List<FileDiff> 提取文件路径，以逗号分隔
  │    │
  │    └─ 3. 调用 OcrmcpClient [新增]
  │         │
  │         ├─ 启动 ocr serve 子进程 (stdio)
  │         ├─ JSON-RPC: initialize → tools/list → tools/call(code_scan)
  │         │
  │         └─ ocr serve 内部:
  │              ├─ 逐文件并发 (goroutine 池)
  │              ├─ Plan 阶段 → LLM 预分析
  │              ├─ Main Loop → tool-use 循环
  │              │    file_read → 读取完整文件
  │              │    code_search → 搜索符号/调用链
  │              │    file_read_diff → 读取其他文件 diff
  │              │    code_comment → 结构化评论 (path + line + existing_code + suggestion)
  │              │    task_done → 审查完成
  │              └─ 返回 JSON: {status, comments[...], summary, warnings}
  │
  └── ReviewReportGenerator [改造]
       ├─ 旧路径: CodeReviewResult sections → 段落式报告
       └─ 新路径: List<OcrComment> → 行级 diff 展示 + 段落式总结
```

### 2.2 审查能力对比

| 能力 | 改造前 | 改造后 |
|------|--------|--------|
| 空指针风险 | LLM 可能提到（不保证） | 逐文件审查，覆盖率强制保证 |
| 事务边界 | 依赖 prompt 提示 | 同上 + background 里 graph 风险信号提醒 |
| 并发安全 | 同上 | 同上 |
| 资源释放 | 同上 | 同上 |
| 异常处理 | 同上 | 同上 |
| 循环依赖 | code-review-graph 静态发现 | code-review-graph 静态发现 |
| 分层违规 | code-review-graph 静态发现 | code-review-graph 静态发现 |
| API 影响范围 | code-review-graph 静态发现 | code-review-graph 静态发现 |
| 行号精度 | 无（LLM 自由描述） | diff hunk 确定性匹配，零漂移 |
| 代码修改建议 | 无 | 行级 existing_code → suggestion_code diff |
| 质量校验 | 无 | Review Filter 二次校验 |

---

## 3. 改动清单

### 3.1 新增类

#### OcrmcpClient (约 120 行)

```
devops-ai-core/src/main/java/com/devops/ai/core/review/ai/OcrmcpClient.java
```

职责：封装 OCR MCP server 的 stdio 通信。

```java
public class OcrmcpClient {
    /**
     * 启动 ocr serve 子进程，完成 MCP 握手，调用指定 tool，返回 JSON 文本。
     *
     * 使用 ProcessBuilder 启动 ocr serve，通过 stdin/stdout 发送 JSON-RPC：
     * 1. {"method":"initialize","params":{...}}
     * 2. {"method":"notifications/initialized"}
     * 3. {"method":"tools/call","params":{"name":"code_scan","arguments":{...}}}
     *
     * @param toolName  要调用的 tool 名称
     * @param arguments tool 的参数 map
     * @return tool 返回的 JSON 文本
     */
    public String callTool(String toolName, Map<String, Object> arguments) throws IOException;

    /**
     * 返回 ocr 二进制文件的路径。
     * 优先级: 系统属性 ocr.bin.path → 环境变量 OCR_BIN → PATH 查找
     */
    private String resolveOcrBinary();

    /**
     * 发送 JSON-RPC 请求并从子进程 stdout 读取响应。
     */
    private JsonNode sendJsonRpc(Process process, JsonNode request);

    /**
     * 检查 ocr 是否可用（通过运行 ocr version 验证）。
     */
    public boolean isAvailable();
}
```

**实现要点**：

1. 子进程生命周期管理——每次 `callTool` 调用启动一个新的子进程（审查完成后自动退出），或复用长连接（通过 MCP 协议的心跳机制）
2. JSON-RPC 帧解析——从 stdout 中逐行读取，按 `Content-Length` 头或换行分隔
3. 超时控制——审查可能耗时较长，需要可配置的超时（默认 30 分钟）
4. 错误处理——子进程崩溃、通信中断、tool 返回 IsError——全部封装为 `OcrException`

#### OcrComment (约 40 行)

```
devops-ai-core/src/main/java/com/devops/ai/core/review/model/OcrComment.java
```

```java
public class OcrComment {
    private String path;           // 文件路径
    private String content;        // 评论内容
    private String existingCode;   // 现有代码
    private String suggestionCode; // 建议代码
    private int startLine;         // 起始行号
    private int endLine;           // 结束行号
    private String thinking;       // LLM 思考过程
}
```

#### OcrReviewResponse (约 30 行)

```java
public class OcrReviewResponse {
    private String status;              // success / completed_with_warnings / completed_with_errors
    private String message;
    private List<OcrComment> comments;
    private OcrSummary summary;         // files_reviewed, total_tokens, elapsed
    private List<OcrWarning> warnings;
    private String projectSummary;      // OCR scan 模式的全局总结

    // 后续可扩展: List<BlameInfo> blame
}

public class OcrSummary {
    private int filesReviewed;
    private int comments;
    private long totalTokens;
    private String elapsed;
}

public class OcrWarning {
    private String file;
    private String message;
    private String type;
}
```

### 3.2 改造类

#### CodeReviewAiService (约 80 行改动)

**现状**：自建 prompt → LlmClient.call() → 正则解析 → CodeReviewResult

**改造后**：提取 graph 上下文 → OcrmcpClient 调用 → JSON 反序列化 → CodeReviewResult

核心方法重写：

```java
public CodeReviewResult review(CodeReviewContext context) {
    // 1. 从 code-review-graph 结果提取上下文作为 background
    String background = buildGraphBackground(context);

    // 2. 提取变更文件路径
    String paths = buildPathList(context.getFileDiffs());

    // 3. 调用 OCR MCP server
    Map<String, Object> args = new HashMap<>();
    args.put("repo_dir", context.getRepoPath());
    args.put("path", paths);                    // 只扫描变更的文件
    args.put("background", background);         // graph 分析结果
    args.put("model", resolveModel());          // 保持用户配置的模型
    args.put("format", "json");                 // JSON 格式

    String ocrJson = ocrmcpClient.callTool("code_scan", args);
    OcrReviewResponse ocrResult = parseOcrResponse(ocrJson);

    // 4. 转换为 CodeReviewResult（兼容现有 ReviewReportGenerator）
    return convertToResult(ocrResult, context);
}

/**
 * 将 code-review-graph 的分析结果转为审查背景文本。
 * 这段文本会作为 background 参数传给 OCR，LLM 在审查时能结合架构信息做判断。
 */
private String buildGraphBackground(CodeReviewContext context) {
    String graphJson = context.getGraphAnalysisJson();
    if (graphJson == null || graphJson.isEmpty()) {
        return "请全面审查代码缺陷：空指针、事务边界、并发安全、异常处理、资源释放。";
    }

    CodeReviewGraph graph = parseGraph(graphJson);
    ImpactScope scope = graph.getImpactScope();

    StringBuilder sb = new StringBuilder();
    sb.append("以下是通过静态分析发现的架构信息，请结合进行代码审查：");

    if (scope != null) {
        sb.append("\\n\\n## 影响范围\\n");
        sb.append("- 风险等级: ").append(scope.getRiskLevel()).append("\\n");
        if (scope.getDirectlyAffectedFiles() != null) {
            sb.append("- 直接影响文件数: ")
              .append(scope.getDirectlyAffectedFiles().size()).append("\\n");
        }
        if (scope.getIndirectlyAffectedFiles() != null) {
            sb.append("- 间接影响文件数: ")
              .append(scope.getIndirectlyAffectedFiles().size()).append("\\n");
        }
    }

    if (scope.getRiskSignals() != null && !scope.getRiskSignals().isEmpty()) {
        sb.append("\\n## 架构风险信号\\n");
        for (String signal : scope.getRiskSignals()) {
            sb.append("- ").append(signal).append("\\n");
        }
    }

    sb.append("\\n## 审查重点\\n");
    sb.append("1. 空指针风险、事务边界(@Transactional)、并发安全、资源释放、异常处理\\n");
    sb.append("2. 被间接影响的文件是否也需要同步修改\\n");
    sb.append("3. 架构风险信号指向的模块是否确实存在设计问题\\n");

    return sb.toString();
}

private CodeReviewResult convertToResult(OcrReviewResponse ocr, CodeReviewContext ctx) {
    CodeReviewResult result = new CodeReviewResult();

    // changeSummary — 来自 OCR 的 projectSummary
    result.setChangeSummary(ocr.getProjectSummary());

    // architectureAnalysis — 保留 code-review-graph 的原始分析
    result.setArchitectureAnalysis(ctx.getGraphSummaryText());

    // codeIssues — 行级评论转为结构化文本
    result.setCodeIssues(formatCommentsAsText(ocr.getComments()));

    // impactAnalysis — 来自 graph 的 ImpactScope
    result.setImpactAnalysis(ctx.getImpactScopeText());

    // 结论
    int commentCount = ocr.getComments() != null ? ocr.getComments().size() : 0;
    if (commentCount == 0) {
        result.setConclusion("通过");
        result.setRiskLevel("低");
        result.setKeyFindings("AI 审查未发现代码缺陷");
    } else {
        result.setConclusion("需修改");
        result.setRiskLevel(deriveRiskLevel(ocr));
        result.setKeyFindings(String.format("发现 %d 个问题，涉及 %d 个文件",
            commentCount, countDistinctFiles(ocr.getComments())));
    }

    result.setRawResponse(ocrJson);  // 保留原始 JSON 便于调试
    return result;
}
```

#### ReviewReportGenerator (约 60 行改动)

增加一个新方法，生成带有行级 diff 展示的报告：

```java
/**
 * 生成增强版报告：行级评论 + 代码 diff 展示。
 * 通过判断 CodeReviewResult 中是否有 rawResponse (OCR JSON) 来自动选择格式。
 */
public String generateEnhanced(CodeReviewResult result, CodeReviewContext context, String format) {
    List<OcrComment> comments = result.getOcrComments();

    // 有行级评论时，使用增强格式
    if (comments != null && !comments.isEmpty()) {
        return generateWithLineComments(result, context, comments, format);
    }

    // 退回到段落式格式
    return generate(result, context, format);
}

private String generateWithLineComments(
        CodeReviewResult result, CodeReviewContext context,
        List<OcrComment> comments, String format) {

    StringBuilder sb = new StringBuilder();
    // ... header (项目、版本、分支、审查时间、commits/files 统计) ...

    sb.append("## 审查摘要").append("\\n\\n");
    sb.append(result.getProjectSummary() != null
        ? result.getProjectSummary()
        : String.format("共发现 %d 个问题", comments.size()));
    sb.append("\\n\\n");

    // 按文件分组
    Map<String, List<OcrComment>> byFile = groupByFile(comments);
    sb.append("## 问题详情").append("\\n\\n");

    for (Map.Entry<String, List<OcrComment>> entry : byFile.entrySet()) {
        sb.append("### `").append(entry.getKey()).append("`\\n\\n");
        for (OcrComment cm : entry.getValue()) {
            sb.append(String.format("**第 %d-%d 行**\\n\\n", cm.getStartLine(), cm.getEndLine()));
            sb.append(cm.getContent()).append("\\n\\n");

            // 代码 diff 展示
            if (cm.getExistingCode() != null && cm.getSuggestionCode() != null) {
                sb.append("```diff\\n");
                for (String line : cm.getExistingCode().split("\\n")) {
                    sb.append("- ").append(line).append("\\n");
                }
                for (String line : cm.getSuggestionCode().split("\\n")) {
                    sb.append("+ ").append(line).append("\\n");
                }
                sb.append("```\\n\\n");
            }
        }
    }

    sb.append("## 审查结论\\n\\n");
    sb.append("**结论**: ").append(result.getConclusion()).append("\\n\\n");
    sb.append("**风险等级**: ").append(result.getRiskLevel()).append("\\n\\n");
    sb.append("**Token 消耗**: ").append(result.getOcrSummary().getTotalTokens()).append("\\n\\n");

    return sb.toString();
}
```

#### CodeReviewResult (约 30 行改动)

新增字段以承载 OCR 的精确输出：

```java
// 新增字段
private List<OcrComment> ocrComments;     // OCR 行级评论
private String ocrStatus;                  // success / completed_with_warnings / ...
private long totalTokens;                  // Token 消耗
private String elapsed;                    // 审查耗时
private String projectSummary;             // 全局摘要

// 新增 getter/setter...
```

#### GenerationOrchestrator (小于 10 行改动)

唯一改动：`context` 中添加 repoPath，并切换到使用 `ReviewReportGenerator.generateEnhanced()`：

```java
// Line 342 附近，添加一行
reviewContext.setRepoPath(cloneDir.getAbsolutePath());  // 传给 OcrmcpClient

// Line 352-353，切换生成器
String reviewContent = reviewReportGenerator.generateEnhanced(
    reviewResult, reviewContext, reviewFormat);
```

### 3.3 不改动的组件

| 组件 | 原因 |
|------|------|
| `CodeReviewDataCollector` | JGit diff 收集逻辑不变 |
| `CodeReviewGraphEngine` | CLI 调用逻辑不变，graph JSON 仍然需要 |
| `DocumentRequest` / `DocumentResult` | API 接口不变 |
| `DocumentApiController` / `GenerateController` | HTTP 层不变 |
| `GenerationOrchestrator` 主流程 | commit 收集、去重、分类、发布文档生成——全部不变 |
| `LlmClient` | 保留——code-review-graph 和旧路径仍可能使用 |

### 3.4 配置项新增

```yaml
# application.yml 新增
ocr:
  bin:
    path: ""          # ocr 二进制路径，空则自动查找
  timeout-minutes: 30  # 审查超时时间
  model: ""           # OCR 使用模型，空则用 llm.modelName
  fallback-on-error: true  # OCR 出错时是否回退到旧的 CodeReviewAiService
```

---

## 4. 实施路线

### Phase 1: MCP Client 基础设施 (1-2 天)

1. 实现 `OcrReviewResponse`、`OcrComment`、`OcrSummary`、`OcrWarning` 模型类
2. 实现 `OcrmcpClient`——子进程管理 + JSON-RPC 通信
3. 编写单元测试（mock ocr 子进程）

### Phase 2: 核心改造 (1-2 天)

1. 改造 `CodeReviewAiService.review()`——替换 prompt 拼装为 MCP 调用
2. 改造 `CodeReviewResult`——新增 OCR 字段
3. 改造 `ReviewReportGenerator`——新增 enhance 格式
4. 微调 `GenerationOrchestrator`——传递 repoPath，切换生成器

### Phase 3: 灰度与回退 (0.5 天)

1. 保留旧 `CodeReviewAiService` 作为 fallback（配置开关控制）
2. 新增 `ocr.fallback-on-error` 配置项

### Phase 4: 端到端测试 (1 天)

1. 准备测试项目（Java Spring Boot 样例）
2. 执行一次完整的 generateSync（useCodeReview=true）
3. 对比新旧输出质量
4. 验证生成的 Markdown/HTML 报告格式

---

## 5. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| ocr 未安装在目标机器上 | 审查功能不可用 | `isAvailable()` 预检 + fallback 到旧 CodeReviewAiService |
| 进程通信死锁 | 审查任务卡死 | 子进程超时 kill + 线程 interrupt |
| OCR 返回格式不兼容 | 解析失败 | Try-catch + fallback + 日志记录 rawResponse |
| code-review-graph CLI 不可用 | 失去架构分析 | graph 为空时仍然工作，只是 background 缺少架构信息 |
| 子进程资源泄漏 | 内存/进程表增长 | try-with-resources + Process.destroyForcibly() |
| 审查时间过长 | 用户体验差 | 可配置超时 + 异步执行（已有 CompletableFuture） |

---

## 6. 代码量估算

| 类别 | 文件 | 新增 | 修改 | 合计 |
|------|------|------|------|------|
| 新增 | `OcrmcpClient.java` | 120 | — | 120 |
| 新增 | `OcrComment.java` | 40 | — | 40 |
| 新增 | `OcrReviewResponse.java` | 60 | — | 60 |
| 改造 | `CodeReviewAiService.java` | 80 | 30 | 110 |
| 改造 | `CodeReviewResult.java` | 30 | 5 | 35 |
| 改造 | `ReviewReportGenerator.java` | 60 | 10 | 70 |
| 改造 | `GenerationOrchestrator.java` | 5 | 5 | 10 |
| 配置 | `application.yml` | 6 | — | 6 |
| **合计** | | **401** | **50** | **451** |

---

## 7. 迁移策略

### 向后兼容

旧的 `CodeReviewAiService.review()` 重命名为 `reviewLegacy()`，保留完整实现。新的 `review()` 作为主路径，配置 `ocr.fallback-on-error=true`（默认）时自动切换。

```java
public CodeReviewResult review(CodeReviewContext context) {
    try {
        if (!ocrmcpClient.isAvailable()) {
            log.info("OCR not available, falling back to legacy review");
            return reviewLegacy(context);
        }
        return reviewWithOcr(context);
    } catch (Exception e) {
        log.error("OCR review failed, falling back to legacy: {}", e.getMessage());
        if (ocrFallbackOnError) {
            return reviewLegacy(context);
        }
        throw new AiServiceException("OCR review failed", e);
    }
}
```

### 部署清单

1. 编译 `ocr` 二进制，放置于 PATH 或指定 `ocr.bin.path`
2. 确认 `ocr config provider` 已配置 LLM endpoint
3. 启动 devops-ai，执行测试审查
4. 观察 `ocr.fallback-on-error` 触发的日志，确认正常
