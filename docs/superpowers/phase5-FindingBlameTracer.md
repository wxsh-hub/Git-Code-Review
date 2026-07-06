# Phase 5: FindingBlameTracer（未修复漏洞 blame 追溯）

## 目标

对代码审查发现的 P0/P1 漏洞做 git blame 追溯，找到漏洞代码的引入者和引入 commit，让效率分析不仅覆盖"已修复的 bug"，也覆盖"当前系统中真实存在的未修复漏洞"。

## 背景

当前项目已经做了两件事——代码审查（发现系统漏洞）和 Bug 归因（对已修复的 bug 做 blame 追溯）。但这两件事之间是断开的：

```
代码审查 → 发现 10 个空指针风险 → 报告里列出了 → 然后就没了
Bug 归因 → 找到已修复的 bug → 追溯谁引入的 → 生成效率统计
```

审查发现的漏洞是谁引入的？没有人追溯过。

如果只追踪"已修复的 bug"而不管"还没修的漏洞"，效率分析只能看到历史。代码审查发现的是**当下系统中真实存在的风险**——这些漏洞还没人修，它们才是真正可能引发事故的。

| 维度 | 已修复 bug 追溯 | 未修复漏洞追溯 |
|------|----------------|--------------|
| 关注点 | 过去发生的事 | 当前存在的风险 |
| 价值 | 事后复盘 | 事前预防 |
| 时效性 | bug 已经修了，信息偏历史 | 漏洞还没修，直接影响代码质量 |
| 可操作性 | 只能看谁"曾经"写了有问题的代码 | 可以推动责任人及时修复 |

## 前置状态

Finding 中 **Phase 1 已定义** 的 blame 相关字段：

| 字段 | 类型 | Phase 1 状态 | 本 Phase 动作 |
|------|------|-------------|-------------|
| `owner` | String | ✅ 已定义 | Phase 1 默认 null，本 Phase 填充 |
| `ownerEmail` | String | ✅ 已定义 | 同上 |
| `blameCommitIds` | List\<String\> | ✅ 已定义（初始化为空列表） | 本 Phase 填充 |
| `candidateHandler` | String | ✅ 已定义 | 本 Phase 填充（三级回退） |

本 Phase **需要新增** 的字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `blameDetails` | Map\<String, BlameShare\> | 每个 commit 的 blame 归属详情 |

## 要做的事情

### 5.1 新建 `FindingBlameTracer.java`

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/ai/FindingBlameTracer.java`

在审查流水线中，审查完成后立即对 P0/P1 的 Finding 执行 git blame 追溯。

**完整流程：**

```
审查流水线（来自 Phase 4）
  │
  ├─ OCR/AI 审查 → List<Finding>
  │    每个 Finding 已有: file, startLine, endLine, evidence, severity, category
  │
  ├─ 【FindingBlameTracer.trace()】 ← 本 Phase
  │    对每个 severity = P0 或 P1 的 Finding:
  │      Step A: git blame -L <startLine>,<endLine> <file>
  │              → 获取每一行的 blame 信息（作者名、邮箱、commit id、commit 时间、commit message）
  │
  │      Step B: 按行数比例计算 blame 份额
  │              同一段代码可能由多人在不同 commit 中编写
  │              实例：
  │                UserService.java 第 42-45 行（4 行）
  │                  blame 第 42 行 → commit bbb222, 张三
  │                  blame 第 43 行 → commit bbb222, 张三（共 2 行）
  │                  blame 第 44 行 → commit ddd444, 李四
  │                  blame 第 45 行 → commit ddd444, 李四（共 2 行）
  │                → 张三 blameShare = 2/4 = 0.5
  │                → 李四 blameShare = 2/4 = 0.5
  │
  │      Step C: 填充 Finding 的归属字段:
  │              → owner = 张三(50%), 李四(50%)
  │              → ownerEmail = zhangsan@corp.com, lisi@corp.com
  │              → blameCommitIds = [bbb222, ddd444]
  │              → candidateHandler = owner 中的主要责任人
  │                （Phase 8 问题处置页使用，格式与 owner 一致含份额）
  │              → 新增 blameDetails: Map<String, BlameShare>
  │                   bbb222 → { author: "张三", share: 0.5, message: "重构登录模块" }
  │                   ddd444 → { author: "李四", share: 0.5, message: "新增导出日志" }
  │
  │      Step D: 对于 blame 无结果的 Finding（P0/P1 级别），回退到文件最后修改者
  │              → 执行 git log -1 --format='%an' -- <file>
  │              → 获取该文件最后一次提交的作者名
  │              → candidateHandler = 最后修改者名
  │              → owner/ownerEmail/blameCommitIds 保持 null
  │
  │      Step E: 降级兜底
  │              → git blame 无结果 + git log 也无结果 → candidateHandler = "待指派"
  │              → P2/P3/P4 级别 Finding 直接跳过，candidateHandler 标记为"待指派"
  │
  └─ 输出：填充了 owner/ownerEmail/blameCommitIds/blameDetails/candidateHandler 的 Finding 列表
```

**核心方法：**
- `List<Finding> trace(List<Finding>, String repoPath)` — 主入口，仅处理 P0/P1。`repoPath` 来自 `CodeReviewContext.getRepoPath()`
- `BlameResult blameFile(String filePath, int startLine, int endLine)` — 对文件执行 git blame
- `Map<String, BlameShare> calculateShares(BlameResult)` — 按行数计算份额

> **实现提示：** FindingBlameTracer 实现 `FindingPostProcessor` 接口（Phase 4 定义），`repoPath` 从 `CodeReviewContext.getRepoPath()` 获取，不需要单独传参。

**数据模型：**

```java
class BlameResult {
    String filePath;
    List<BlameLine> lines;
}

class BlameLine {
    int lineNumber;
    String commitId;
    String authorName;
    String authorEmail;
    long commitTime;
    String commitMessage;
}

class BlameShare {
    String authorName;
    String authorEmail;
    String commitId;
    String commitMessage;
    long commitTime;
    double share;  // 0.0 ~ 1.0
}
```

### 5.2 集成到审查后处理管线

FindingBlameTracer 作为 `FindingPostProcessor` 接口（Phase 4 定义）的实现，插入到 Phase 4 搭建的后处理管线中：

```
审查后处理管线（位于 CodeReviewAiService，Phase 4 搭建）

  原始 Finding 列表（Step 3 产出）
    │
    ├─ 【FindingBlameTracer.trace()】 ← 本 Phase 插入点
    │    仅处理 P0/P1 的 Finding
    │    填充 owner / ownerEmail / blameCommitIds / blameDetails / candidateHandler
    │
    ├─ review LLM 交叉验证（Phase 6 插入）← 使用 blame 追溯结果作为验证输入
    │    更新 confidence + status + severity + category + reviewer
    │
    ├─ SecretDetector 脱敏（Phase 3 已注入）
    │
    ├─ FindingVerifier 校验（Phase 3 已注入）
    │
    └─ 生成报告
```

**集成方式：** 实现 `FindingPostProcessor` 接口，在 Phase 4 的管线中于 SecretDetector 之前注册 `new FindingBlameTracer()`。

### 5.3 修改 `CodeReviewAiService.java`

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/ai/CodeReviewAiService.java`

在管线注册代码中（SecretDetector 之前）插入 `new FindingBlameTracer()` 处理器。

### 5.4 修改 `Finding.java` — 新增 blameDetails 字段

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/model/Finding.java`

```java
// blame 详情（本阶段新增）
Map<String, BlameShare> blameDetails;   // key = commitId

// 已有字段（Phase 1 已定义，本阶段首次填充值）
String owner;           // 引入者，多人用逗号分隔，格式："张三(50%), 李四(50%)"
String ownerEmail;      // 引入者邮箱，多人用逗号分隔
List<String> blameCommitIds;  // 追溯到的 commit id 列表

// 问题处置（Phase 1 已定义，本阶段首次填充值）
String candidateHandler;  // 候选处理人，三级回退：
                           //   1) blame owner（P0/P1 有 blame 结果时）
                           //   2) git log -1 --format='%an' -- <file>（blame 无结果时）
                           //   3) "待指派"（git log 也无结果时，P2/P3/P4 直接到此）
```

## 涉及文件

| 操作 | 文件 | 路径 |
|------|------|------|
| 新增 | FindingBlameTracer.java | review/ai/ |
| 新增 | BlameResult.java | review/model/ |
| 新增 | BlameLine.java | review/model/ |
| 新增 | BlameShare.java | review/model/ |
| 修改 | Finding.java | review/model/（新增 blameDetails 字段） |
| 修改 | CodeReviewAiService.java | review/ai/（管线注册） |

## 验收标准

- [ ] FindingBlameTracer 仅对 P0/P1 的 Finding 执行 blame，P2/P3/P4 跳过
- [ ] JGit blame 正确获取每行的作者名、邮箱、commit id、commit 时间、commit message
- [ ] 多人共写同一段代码时按行数比例正确计算份额（所有份额相加 = 1.0）
- [ ] owner 字段格式正确："张三(50%), 李四(50%)"
- [ ] blameCommitIds 去重（同一 commit 多行只记录一次）
- [ ] blameDetails Map 正确记录每个 commit 的归属信息
- [ ] candidateHandler 按三级回退：blame owner → git log 最后修改者 → "待指派"
- [ ] 非 git 仓库或无 blame 信息时优雅降级（owner 保持 null，candidateHandler 标记"待指派"，不抛异常）
- [ ] 报告中正确展示引入者和引入 commit 信息

## 下一阶段依赖

Phase 6（双 LLM 交叉验证 + 证据链）依赖本阶段产出的 blame 追溯结果作为 review LLM 的输入。
Phase 8（报告结构调整）使用 candidateHandler 作为问题处置页的候选处理人。
