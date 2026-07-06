# Phase 2: OCR MCP 模型与客户端

## 目标

接入 open-code-review MCP server，替换当前 LLM 单次 prompt 调用模式，实现逐文件行级精准审查，解决大 diff token 爆炸和行号漂移问题。

## 背景

当前 `CodeReviewAiService` 把整个 diff 一次性塞进一个 LLM prompt：
- 大变更集 token 爆炸，LLM 走捷径跳过文件
- 行号靠 LLM 自由描述，经常漂移
- 输出格式靠正则匹配 "### " 分隔符，格式一偏就解析失败

改造方案：保留 code-review-graph 架构分析能力不变，用 open-code-review MCP server 替换 LLM 直接调用。

## 当前状态

三块核心代码**已实现**（在 Phase 2 文档编写之前已按 open-code-review 源码写出），本 Phase 的工作是**纳入版本管理并修正文档与实际代码的差异**。

| 文件 | 状态 | 说明 |
|------|------|------|
| OcrComment.java | ✅ 已存在 | 7 字段完整，用于反序列化 OCR 返回的 JSON。**注意：当前 CodeReviewResult 和 ReviewReportGenerator 直接使用 OcrComment，此耦合将在 Phase 4 切换到 Finding 后解除。** |
| OcrReviewResponse.java | ✅ 已存在 | 字段比原始设计更丰富（见下方差异表） |
| OcrmcpClient.java | ✅ 已存在 | stdio JSON-RPC 完整实现，含心跳/超时/降级 |
| CodeReviewAiService | ✅ 已集成 | reviewWithOcr() / reviewLegacy() 双路径 + 自动 fallback |
| application.yml | ✅ 已配置 | `ocr.bin.path` / `ocr.timeout-minutes` / `ocr.fallback-on-error` |

### OcrReviewResponse 实际字段 vs 原始设计

| 字段 | 原始设计 | 实际代码 | 结论 |
|------|---------|---------|------|
| warnings 类型 | `List<String>` | `List<OcrWarning>`（含 file/message/type） | 以实际为准，更丰富 |
| summary.totalTokens | `int` | `long` | 以实际为准 |
| summary.elapsed | `long`（毫秒） | `String`（"12.3s" 格式） | 以实际为准 |
| summary 额外字段 | 无 | `inputTokens` / `outputTokens` / `cacheReadTokens` / `cacheWriteTokens` | open-code-review 原生输出，保留 |
| 顶层额外字段 | 无 | `message` / `projectSummary` / `toolCalls` | scan 模式的全局摘要和调用统计，保留 |

### OCR 输出能力边界

open-code-review (Go) 的 `LlmComment` 模型只输出 7 个字段（path / content / existing_code / suggestion_code / start_line / end_line / thinking），**不输出结构化 severity / category / confidence**。这三个值由后续 Phase 补齐：

```
OCR 输出（7字段）
  │
  ├─ Phase 1: Finding.fromOcrComment() 用关键词推断 severity/category（fallback）
  │
  └─ Phase 6: 双 LLM 交叉验证 → 确定最终 confidence + status
       → 届时 severity/category 由 review LLM 结构化输出覆盖关键词推断结果
```

## 要做的事情

### 2.1 OcrComment.java — 纳入版本管理

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/model/OcrComment.java`

OCR MCP 的**中间模型**，仅用于反序列化 OCR 返回的原始 JSON。

| 字段 | 类型 | 说明 |
|------|------|------|
| path | String | 文件路径 |
| content | String | 问题描述 |
| existingCode | String | 现有代码片段 |
| suggestionCode | String | 建议修改的代码（diff 格式） |
| startLine | int | 起始行号 |
| endLine | int | 结束行号 |
| thinking | String | OCR 的思考过程/分析 |

下游通过 `Finding.fromOcrComment()` 转换后再流通。

> **已知耦合（Phase 4 解决）：** `CodeReviewResult.ocrComments` 和 `ReviewReportGenerator` 当前直接引用 OcrComment。Phase 4 将 CodeReviewAiService 输出统一为 `List<Finding>`，届时 ReviewReportGenerator 切换到 Finding 渲染，OcrComment 回归反序列化中间层。

### 2.2 OcrReviewResponse.java — 纳入版本管理

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/model/OcrReviewResponse.java`

OCR MCP 返回的整体响应模型。

| 字段 | 类型 | 说明 |
|------|------|------|
| status | String | success / completed_with_warnings / completed_with_errors / skipped |
| message | String | 人类可读的状态消息 |
| comments | List\<OcrComment\> | 结构化评论列表 |
| summary | OcrSummary | 审查摘要（token/耗时统计） |
| warnings | List\<OcrWarning\> | 警告信息（含 file / message / type） |
| projectSummary | String | scan 模式的全局项目摘要 |
| toolCalls | Map\<String, Object\> | 各 tool 的调用次数统计 |

OcrSummary 内嵌类（以 open-code-review 实际输出为准）：

| 字段 | 类型 | 说明 |
|------|------|------|
| filesReviewed | int | 审查的文件数 |
| comments | int | 发现的评论/问题总数 |
| totalTokens | long | 消耗的总 token 数 |
| inputTokens | long | 输入 token 数 |
| outputTokens | long | 输出 token 数 |
| cacheReadTokens | long | 缓存读取 token 数 |
| cacheWriteTokens | long | 缓存写入 token 数 |
| elapsed | String | 耗时（"12.3s" 格式） |

### 2.3 OcrmcpClient.java — 纳入版本管理

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/ai/OcrmcpClient.java`

stdio JSON-RPC 通信客户端，负责与 OCR MCP server 子进程交互。

**核心能力：**

```
OcrmcpClient
  ├─ 启动 ocr serve 子进程（ProcessBuilder）
  ├─ initialize → 握手 + 能力协商
  ├─ tools/call 调用：
  │    ├─ code_review_diff：只审查变更行（有 git hash 时）
  │    └─ code_scan：全文件扫描（无 diff 上下文时）
  ├─ 子进程生命周期管理（启动/心跳/超时/关闭）
  ├─ isAvailable() 预检方法
  └─ 30 分钟超时保护
```

**JSON-RPC 协议实现要点：**
- 使用 stdin/stdout 做 JSON-RPC 通信
- 每次请求生成唯一 id
- 支持 `initialize`、`tools/call` 方法
- 解析返回的 JSON 为 `OcrReviewResponse`

**降级策略：**
- `isAvailable()` 返回 false 时自动 fallback 到旧 LLM 路径
- OCR 子进程启动失败时回退到旧 LLM 路径
- 保留旧 `CodeReviewAiService` 原有逻辑完整不变

**ocr serve 内部行为（open-code-review 实际实现，仅供参考）：**
- 逐文件并发（goroutine 池，默认 8 并发）
- 每个文件：Plan 阶段 → Main 阶段（LLM tool-use loop）→ Review Filter
- file_read → 读取完整文件
- code_search → 搜索符号/调用链
- code_comment → 结构化评论（path + line + existing_code + suggestion）
- 返回 JSON: `{status, comments[...], summary, warnings, project_summary, tool_calls}`

### 2.4 配置项确认

`application.yml` 中已存在的 OCR 配置（以实际为准）：

```yaml
ocr:
  bin:
    path: "./bin/ocr.exe"       # ocr 可执行文件路径
  timeout-minutes: 30            # 子进程超时时间
  model: ""                      # OCR 使用的 LLM 模型，空则用 llm.modelName
  fallback-on-error: true        # OCR 出错时是否降级到旧 LLM 路径
```

**不需要的配置项（从原始设计中移除）：**
- ~~`ocr.mcp.enabled`~~ — 改用 `OcrmcpClient.isAvailable()` 运行时检测
- ~~`ocr.mcp.max-files-per-request`~~ — OCR 服务器内部并发控制，Java 侧无需限制

## 涉及文件

| 操作 | 文件 | 路径 |
|------|------|------|
| 纳入版本 | OcrComment.java | review/model/ |
| 纳入版本 | OcrReviewResponse.java | review/model/ |
| 纳入版本 | OcrmcpClient.java | review/ai/ |
| 无需修改 | application.yml | bootstrap/src/main/resources/（已配置完成） |

## 验收标准

- [x] OcrComment 七个字段完整定义，可被 Jackson 正确反序列化
- [x] OcrReviewResponse 包含 status / comments / summary / warnings（实际字段比设计更丰富）
- [x] OcrmcpClient 实现 JSON-RPC initialize → tools/call 完整调用链
- [x] OcrmcpClient.isAvailable() 可在 OCR 未安装时返回 false 而不抛异常
- [x] 30 分钟超时配置可通过 application.yml 调整
- [x] OCR 不可用时自动 fallback 到旧 LLM 路径，原有功能不受影响
- [ ] `git add` 三个 untracked 文件，纳入版本管理
- [ ] Phase 4 将 CodeReviewAiService 输出统一为 List\<Finding\>，解除 OcrComment 的下游耦合

## 下一阶段依赖

Phase 4（审查流水线改造）会调用 OcrmcpClient 作为实际审查执行器。
Phase 3（SecretDetector + FindingVerifier）独立于 OCR 通道，在审查后处理管线中运行。
