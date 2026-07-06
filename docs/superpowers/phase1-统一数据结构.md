# Phase 1: Finding 统一数据结构 + 四个枚举

## 目标

建立全系统统一的代码问题数据模型 `Finding`，让 OCR MCP、旧 LLM、Bug 归因、Secret 检测四个来源的问题都收敛到同一个数据结构，消除 `OcrComment` / `CodeReviewResult` / `BugDetail` 各自为政的问题。

## 背景

当前三个子系统各自定义"问题"模型：
- OCR 输出用 `OcrComment`（path/content/existingCode/suggestionCode）
- 审查结果用 `CodeReviewResult`（codeIssues/keyFindings/riskLevel）
- Bug 归因用 `BugDetail`（commitId/introducedBy/fixedBy）

字段不统一，互相转换时信息丢失，下游报告生成需要分别处理三种格式。

## 要做的事情

### 1.1 新建 `Finding.java`

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/model/Finding.java`

```
Finding {
    id               // UUID 短码，唯一标识
    file             // 文件路径
    startLine        // 起始行号（1-indexed）
    endLine          // 结束行号（1-indexed）
    severity         // FindingSeverity 枚举
    category         // FindingCategory 枚举

    // 置信度
    confidence            // 最终置信度 0.0-1.0
    reviewConclusion     // 第二个 LLM 的复核结论

    // 证据链
    evidence         // 问题所在的代码片段
    trigger          // 什么输入/场景触发此问题
    suggestedFix     // 建议修复方式

    // 归属
    owner            // 责任人（来自 git blame）
    ownerEmail       // 责任人邮箱
    blameCommitIds   // 追溯到的 commit id 列表

    // 状态与复核
    status           // FindingStatus 枚举：CONFIRMED / FALSE_POSITIVE

    // 问题处置（Phase 8 使用，由各前置 Phase 填充）
    codeLink         // 代码链接（Phase 8 当场拼接，从 CodeReviewContext.gitRemoteUrl + branch + file + line 生成）
    candidateHandler // 候选处理人（Phase 5 blame 追溯时填充，取自 owner）
    reviewer         // 复核人标识（Phase 6 交叉验证时填充，值为 "review LLM"）
    deadline         // 建议修复截止时间（Phase 8 自动计算：P0="立即"，P1=reviewDate+3工作日，P2=reviewDate+7工作日）

    // 模块归属（Phase 4 填充，Phase 8 模块趋势页聚合）
    moduleName       // 所属业务模块名（Phase 4 Step 3 分组时由 ReviewGroup.name 填充）
}
```

必须包含的转换方法：
- `static Finding fromOcrComment(OcrComment)` — OCR MCP 输出 → Finding
- `static Finding fromCodeReviewResult(CodeReviewResult)` — 旧 LLM 输出 → Finding
- `static Finding fromBugDetail(BugDetail)` — Bug 归因 → Finding
- `void sanitize()` — 对所有文本字段做敏感信息脱敏

**置信度计算规则：**
- 未复核时 `confidence` 保持第一个 LLM 的初始值
- 复核后 `confidence = (分类LLM置信度 + reviewLLM置信度) / 2`
- `confidence ≥ 0.7` → 展示为"已确认"，进入正式统计
- `confidence < 0.7` → 标记为"误报"，不展示

### 1.2 新建 `FindingSeverity.java`

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/model/FindingSeverity.java`

| 枚举值 | 标签 | 含义 |
|--------|------|------|
| BLOCKER | P0 | 阻断：安全漏洞、数据丢失风险 |
| HIGH | P1 | 高危：空指针、事务边界、并发安全 |
| MEDIUM | P2 | 中危：资源泄漏、错误处理缺失 |
| LOW | P3 | 低危：代码风格、命名规范 |
| INFO | P4 | 信息：建议性改进 |

### 1.3 新建 `FindingCategory.java`

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/model/FindingCategory.java`

| 枚举值 | 含义 |
|--------|------|
| SECURITY | 安全漏洞（SQL注入/XSS/权限绕过等） |
| NPE | 空指针风险 |
| TRANSACTION | 事务边界缺失 |
| CONCURRENCY | 并发安全问题 |
| RESOURCE_LEAK | 资源泄漏（连接/流/文件句柄） |
| ERROR_HANDLING | 异常处理不当 |
| SECRET_EXPOSURE | 敏感信息暴露（密码/Token/密钥） |
| CODE_STYLE | 代码风格问题 |
| PERFORMANCE | 性能问题 |
| DEPENDENCY | 依赖风险（过期/漏洞/SNAPSHOT） |
| ARCHITECTURE | 架构问题（循环依赖/分层违规） |
| LOGIC_ERROR | 逻辑错误 |
| HARDCODED | 硬编码 |
| OTHER | 其他 |

### 1.4 新建 `FindingStatus.java`

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/model/FindingStatus.java`

| 枚举值 | 含义 |
|--------|------|
| CONFIRMED | 已确认（双 LLM 置信度平均 ≥ 0.7） |
| FALSE_POSITIVE | 误报（双 LLM 置信度平均 < 0.7） |

## 涉及文件

| 操作 | 文件 | 路径 |
|------|------|------|
| 新增 | Finding.java | review/model/ |
| 新增 | FindingSeverity.java | review/model/ |
| 新增 | FindingCategory.java | review/model/ |
| 新增 | FindingStatus.java | review/model/ |

## 验收标准

- [ ] 四个模型类编译通过，无编译错误
- [ ] Finding 包含当前阶段需要的 20 个字段（core 15 个 + 问题处置 4 个 + 模块归属 1 个；blameDetails 等 blame 追溯字段将在 Phase 5 新增，此处暂不定义）
- [ ] Finding.fromOcrComment() / fromCodeReviewResult() / fromBugDetail() 三个转换方法签名定义完整
- [ ] Finding.sanitize() 方法签名定义完整
- [ ] FindingSeverity 五个级别、FindingCategory 十四个分类、FindingStatus 两个状态全部定义
- [ ] 枚举值均有对应的中文标签

## 下一阶段依赖

Phase 2~8 全部依赖本阶段的 Finding 及其三个辅助枚举。这是整个改造方案的**数据地基**。
