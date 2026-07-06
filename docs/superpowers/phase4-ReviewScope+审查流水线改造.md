# Phase 4: ReviewScope + 审查流水线改造

## 目标

1. 引入 `ReviewScope` 枚举，统一报告口径，每份报告在开头标明审查范围
2. 将审查流程从"全量 diff 一次性灌给 AI"改造为"文件预筛选 → 按业务链路分组 → 逐组调用 OCR MCP"的三步流水线

## 背景

**口径问题：** 当用户选了分支范围审查（比如从某个 commit 开始有 78 个 commit 迭代），生成的报告里前面写"78 个 commit 迭代"，后面又出现"初始提交/创建项目骨架"——因为全量扫描时自动把 root commit 当起点、分支审查时又用用户指定的范围，两种逻辑混在同一个 `generateSync()` 里，读者完全不知道这报告审的是什么范围。

**流水线问题：** 一次把几十个文件的 diff 塞进一个 LLM prompt，大文件超过 5000 字符就直接丢弃，AI 也无法跨文件理解 Controller → Service → Mapper 的完整调用链。

## 前置状态

CodeReviewContext 中 **Phase 1 已添加** 的字段：

| 字段 | 状态 | 说明 |
|------|------|------|
| `sinceHash` | ✅ 已有 | Phase 1 添加，`reviewDiff()` 中传入 |
| `untilHash` | ✅ 已有 | Phase 1 添加，`reviewDiff()` 中传入 |
| `gitRemoteUrl` | ✅ 已有 | Phase 1 添加，Phase 8 拼接 codeLink |
| `repoPath` | ✅ 已有 | 仓库本地路径 |
| `branch` | ✅ 已有 | 分支名 |

本 Phase **需要新增** 的字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `reviewScope` | ReviewScope | 审查范围枚举 |
| `scopeDescription` | String | 范围描述文本（如"自 2025-01-15 起共 78 个 commit"） |
| `commitCount` | int | commit 数量 |
| `reviewDate` | Date | 审查日期（Phase 8 用于截止时间计算） |

## 要做的事情

### 4.1 新建 `ReviewScope.java`

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/model/ReviewScope.java`

```java
enum ReviewScope {
    DIFF_REVIEW   ("本次 diff 审查", "仅检查两个 commit 之间的代码变更"),
    BRANCH_REVIEW ("分支范围审查",   "检查该分支上指定范围内的所有变更"),
    FULL_SCAN     ("全量项目扫描",   "扫描整个项目的所有源文件"),
}
```

**判断逻辑（在 GenerationOrchestrator 中实现）：**

| 条件 | 口径 |
|------|------|
| 用户指定了 sinceHash + untilHash，且 commits = 1 | DIFF_REVIEW |
| 用户指定了 sinceHash + untilHash，且 commits > 1 | BRANCH_REVIEW |
| 无指定 hash，走全量扫描 | FULL_SCAN |

**报告中必须体现（出现在报告第一行）：**

> **审查范围**：分支范围审查（自 2025-01-15 起共 78 个 commit）

### 4.2 修改 `CodeReviewContext.java`

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/model/CodeReviewContext.java`

**新增字段**（sinceHash / untilHash / gitRemoteUrl 已在 Phase 1 添加，此处不重复）：

- `ReviewScope reviewScope` — 审查范围
- `String scopeDescription` — 范围描述文本（如"自 2025-01-15 起共 78 个 commit"）
- `int commitCount` — commit 数量
- `Date reviewDate` — 审查日期（Phase 8 用于截止时间计算，P1=审查日期+3工作日）

### 4.3 修改 `GenerationOrchestrator.java` — 添加 ReviewScope 判定 + 初始化上下文

路径：`devops-ai-core/src/main/java/com/devops/ai/core/generator/GenerationOrchestrator.java`

在 `generateSync()` 入口处完成以下初始化工作：

1. **ReviewScope 判定** — 根据用户请求参数确定：
   - 1 个 commit → DIFF_REVIEW
   - 多个 commit → BRANCH_REVIEW
   - 无 hash → FULL_SCAN

2. **reviewDate** — 设置为当前时间 `new Date()`，写入 CodeReviewContext（Phase 8 用于截止时间计算）

3. **gitRemoteUrl** — 在 `CodeReviewDataCollector` clone 完成后，通过 JGit API 获取远程地址（`git.getRepository().getConfig().getString("remote", "origin", "url")`），写入 CodeReviewContext（Phase 8 用于 codeLink 拼接）
   - **必须用 try-catch 包裹**，避免非 git 仓库或 clone 失败时中断整个 generateSync()
   - 获取失败时置 null，Phase 8 跳过 codeLink 生成
   - 不要在 GenerationOrchestrator 中直接 Runtime.exec()，JGit API 更稳定

### 4.4 审查流水线三步改造

在 `CodeReviewAiService` 中将原来的单次 LLM 调用改造为三步流水线：

```
Step 1: 文件预筛选
  输入：本次审查涉及的完整文件列表
  处理：
    ├─ 跳过 DELETE 类型的文件（已删除的文件不需要审查）
    └─ 跳过非代码文件（图片、二进制、文档等）
  输出：筛选后的代码文件列表
  实现方法：List<FileDiff> preFilter(List<FileDiff> diffs)

Step 2: 按业务链路分组
  输入：筛选后的代码文件列表 + code-review-graph 的架构信息
  处理：
    ├─ 从文件路径提取业务领域名，规则：
    │    - 从路径的最后一个有意义目录名提取
    │    - 跳过框架目录：java, src, main, resources, controller,
    │      service, mapper, dao, model, entity, config, util, common
    │    - 示例：src/main/java/com/example/user/UserController.java → "user"
    │
    ├─ 按领域名分组：
    │    UserController.java  ─┐
    │    UserService.java     ─┤ → 组 "user"
    │    UserMapper.xml       ─┘
    │    OrderController.java ─┐
    │    OrderService.java    ─┤ → 组 "order"
    │    OrderMapper.xml      ─┘
    │
    └─ 每组构建 background 上下文：
        - 该链路的跨文件调用关系（从 code-review-graph 获取）
        - 架构风险信号（循环依赖、分层违规等）
        - 审查重点提示（空指针、事务、并发等）
  实现方法：Map<String, ReviewGroup> groupByBusinessLink(List<FileDiff> files)

Step 3: 逐组调用 OCR MCP
  输入：每个 ReviewGroup（文件列表 + background 上下文）
  处理：
    ├─ 优先使用 OcrmcpClient（Phase 2 产物）
    │    - 每组 path 参数 = 该组文件列表（逗号分隔）
    │    - background = 链路上下文信息
    │    - OCR 内部用 file_read / code_search 按需读取
    ├─ OCR 不可用时 fallback 到旧 LLM 路径
    │    - 每组独立构建 prompt
    │    - 控制每组的文件数和 diff 大小
    ├─ 汇总所有组的 OcrComment 列表
    ├─ **转换为 Finding**：每个 OcrComment 通过 `Finding.fromOcrComment()` 转为 Finding
    │    - 此时 severity/category/confidence 为关键词推断值（Phase 1 fallback）
    │    - Phase 6 review LLM 交叉验证后会覆盖这些值
    └─ 为每个 Finding 填充 moduleName = ReviewGroup.name
         （Phase 8 模块趋势页按此字段聚合风险）
  实现方法：List<Finding> reviewGroup(ReviewGroup group)
```

**关键变更：Step 3 输出统一为 `List<Finding>`，不再暴露 `CodeReviewResult`。** 这意味着：
- `CodeReviewResult.ocrComments` 不再需要对外暴露（保留内部使用）
- `ReviewReportGenerator` 的渲染从 OcrComment 切换到 Finding（本 Phase 逐步迁移）

**分组数据模型：**

```java
class ReviewGroup {
    String name;                  // 业务链路名，如 "user"
    List<FileDiff> files;       // 该组的文件列表
    String background;            // 审查背景上下文（调用链/架构信号/审查重点）
    List<ReviewGroup> dependencies; // 依赖的其他组（如 user 依赖 auth）
}
```

### 4.5 共享工具：ModulePathResolver（Phase 8 复用）

路径提取逻辑需要同时被 Phase 4（分组）和 Phase 8（模块风险分布）使用，必须抽成独立工具类，不能作为 CodeReviewAiService 的私有方法。

**新建 `ModulePathResolver.java`**，路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/ai/ModulePathResolver.java`

```java
public class ModulePathResolver {
    // 框架目录名，跳过不做模块名
    private static final Set<String> FRAMEWORK_DIRS = Set.of(
        "java", "src", "main", "resources", "controller",
        "service", "mapper", "dao", "model", "entity", "config", "util", "common"
    );

    /** 从文件路径提取业务领域名 */
    public static String resolveModule(String filePath) {
        // 从路径的最后一个有意义目录名提取，跳过框架目录
        // 示例：src/main/java/com/example/user/UserController.java → "user"
    }
}
```

### 4.6 审查后处理管线

在 Step 3 产出原始 Finding 列表之后、生成报告之前，预留一个可扩展的后处理管线（PostProcessor 链）。各 Phase 按顺序向管线中注册处理器。

**管线框架（本 Phase 搭建，后续 Phase 填充）：**

```
Step 3 产出 List<Finding>（原始）
  │
  ├─ [Phase 5 插入] FindingBlameTracer.trace()
  │    对 P0/P1 Finding 执行 git blame，填充 owner/ownerEmail/blameCommitIds/candidateHandler
  │
  ├─ [Phase 6 插入] ReviewLlmService.crossValidate()
  │    双 LLM 交叉验证，更新 confidence 和 status
  │    ↑ 必须在 SecretDetector 之前——review LLM 需要看到完整原文做判断
  │    ↑ 从此处开始 confidence 是双 LLM 平均值，severity/category 可能被 review LLM 覆盖
  │
  ├─ [Phase 3] SecretDetector.detectAndSanitize()
  │    所有 Finding 文本字段脱敏，新增 SECRET_EXPOSURE 类型的 Finding
  │
  ├─ [Phase 3] FindingVerifier.verify()
  │    行号校验 → 去重 → 误报检测 → 触发条件完整性
  │    ↑ 使用 Phase 6 输出的最终 confidence
  │
  └─ 输出最终 Finding 列表，进入报告生成
```

**本 Phase 的实现策略：**

Phase 4 先定义一个 `FindingPostProcessor` 接口，管线中当前只注册 SecretDetector 和 FindingVerifier。Phase 5/6 实现时在链前端插入新处理器即可，不修改管线框架代码。

```java
// 管线定义（在 CodeReviewAiService 中）
interface FindingPostProcessor {
    List<Finding> process(List<Finding> findings, CodeReviewContext context);
}

// 本 Phase 的管线组装
List<FindingPostProcessor> pipeline = Arrays.asList(
    // Phase 5 在此插入: new FindingBlameTracer()
    // Phase 6 在此插入: new ReviewLlmCrossValidator()
    secretDetector,
    findingVerifier
);
```

### 4.6 修改 `CodeReviewAiService.java`

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/ai/CodeReviewAiService.java`

改动内容：
- 将原来的 `reviewDiff()` 方法重构为三步流水线
- **Step 3 输出改为 `List<Finding>`**（不再返回 CodeReviewResult）
- 注入 OcrmcpClient（可选，OCR 不可用时降级）
- 定义 `FindingPostProcessor` 接口，搭建后处理管线框架
- 注入 SecretDetector + FindingVerifier（通过管线）
- 管线中标注 Phase 5（FindingBlameTracer）/ Phase 6（review LLM）的插入位置
- 保留旧 LLM 路径作为 fallback

## 涉及文件

| 操作 | 文件 | 路径 |
|------|------|------|
| 新增 | ReviewScope.java | review/model/ |
| 新增 | ModulePathResolver.java | review/ai/（Phase 8 模块风险分布复用） |
| 修改 | CodeReviewContext.java | review/model/（新增 reviewScope, scopeDescription, commitCount, reviewDate。sinceHash/untilHash/gitRemoteUrl 已在 Phase 1 添加） |
| 修改 | GenerationOrchestrator.java | generator/（ReviewScope 判定 + git remote 获取用 JGit API） |
| 修改 | CodeReviewAiService.java | review/ai/（三步流水线 + OcrComment→Finding 转换 + 注入 FindingPostProcessor 管线） |

## 验收标准

- [ ] ReviewScope 枚举三个值定义完整，有中文标签和描述
- [ ] GenerationOrchestrator 正确根据条件判定 ReviewScope（1 个 commit → DIFF，多个 → BRANCH，无 hash → FULL）
- [ ] 每份报告第一行显示审查范围信息
- [ ] 文件预筛选正确跳过 DELETE 文件和非代码文件
- [ ] 业务链路分组正确提取领域名、跳过框架目录名
- [ ] 同领域 Controller/Service/Mapper 正确分组到同一链路
- [ ] 每组构建的 background 包含调用关系和架构信号
- [ ] 逐组调用 OCR MCP 时正确传 path 和 background 参数
- [ ] OCR 不可用时正确 fallback 到旧 LLM 路径，原有功能不退化
- [ ] **Step 3 输出为 `List<Finding>`，不再对外暴露 CodeReviewResult.ocrComments**
- [ ] 每个 Finding 的 moduleName 正确填充为 ReviewGroup.name
- [ ] 旧 LLM 路径的 prompt 大小被控制在合理范围（不再一次灌大 diff）
- [ ] 后处理管线框架搭建完成，定义了 `FindingPostProcessor` 接口
- [ ] 管线中当前注入 SecretDetector + FindingVerifier
- [ ] Phase 5/6 的处理器插入位置已用注释标注，顺序为：BlameTracer → review LLM → SecretDetector → FindingVerifier

## 下一阶段依赖

Phase 5（FindingBlameTracer）依赖本阶段的审查流水线产出的 Finding 列表，并在本阶段预留的后处理管线中插入 BlameTracer 处理器。
