# Phase 6: Bug 归因状态 + 双 LLM 交叉验证 + 证据链

## 目标

1. 为 BugDetail 引入 `FindingStatus` 状态字段（CONFIRMED / FALSE_POSITIVE）
2. 实现双 LLM 交叉验证机制——两个 LLM 独立判定置信度，取平均值决定归因结果
3. **让 review LLM 结构化输出 severity + category + confidence**，解决 Phase 1-5 中 severity/category 只能靠关键词推断的问题
4. 为 P0/P1 问题补齐完整证据链（evidence → trigger → suggestedFix）

## 背景

### 核心问题：severity/category/confidence 的来源

OCR（open-code-review）的 `LlmComment` 只输出 7 个字段（path/content/existing_code/suggestion_code/start_line/end_line/thinking），**不输出结构化 severity/category/confidence**。Phase 1 的 `fromDescription()` 只能做关键词 fallback，准确率有限。

**本 Phase 是 severity/category/confidence 从"关键词猜测"升级为"AI 确认"的分水岭。** 管线排在 Phase 6 之后的模块（SecretDetector、FindingVerifier、报告生成）使用的是双 LLM 验证后的最终置信度。

### 效率分析侧

当前 `fix commit + git blame` 只能说明"修复的这几行代码是张三某次提交写的"，不能直接等于"张三引入了这个 bug"。现在的代码直接把 blame 结果等同于 bug 引入，统计中不做区分。

改造后：blame 追溯到原始 commit 后，不直接判定为 bug，而是让**另一个 LLM（review LLM）**去复核这个追溯结果。两个 LLM 各自给出置信度，取平均值：≥ 0.7 展示为"已确认"，< 0.7 判定为"误报"不展示。

## 要做的事情

### 6.1 双 LLM 交叉验证流程

```
Phase 5 输出: Finding（已填充 owner/blameCommitIds，但 severity/category 仍是关键词推断）

  │
  ├─ Phase 6 交叉验证
  │
  │   输入:
  │     - Finding 的 evidence（代码片段）
  │     - Finding 的 severity / category（Phase 1 关键词推断值，作为参考）
  │     - Finding 的 confidence（Phase 1 初始值，如 0.75）
  │     - blame 追溯结果（owner, blameCommitIds, commit message）
  │     - 原始文件的上下文代码
  │
  │   review LLM 独立判定并结构化输出（JSON）:
  │     {
  │       "confidence": 0.85,
  │       "severity": "P1",
  │       "category": "NPE",
  │       "reason": "findById 返回 null 后未做检查直接调用 getName，确实存在 NPE 风险",
  │       "trigger": "当传入的 id 不存在于数据库时，findById 返回 null",
  │       "suggestedFix": "用 Optional.ofNullable(user).orElseThrow(...) 包装"
  │     }
  │
  │   对 Finding 的更新:
  │     → confidence = (原 confidence + review 置信度) / 2
  │     → severity = review LLM 输出的 severity（覆盖关键词推断值）
  │     → category = review LLM 输出的 category（覆盖关键词推断值）
  │     → reviewConclusion = review LLM 的 reason
  │     → reviewer = "review LLM"
  │     → trigger = review LLM 的输出（如果原来为空或不够精确）
  │     → suggestedFix = review LLM 的输出（如果原来为空）
  │
  │   状态判定:
  │     → confidence ≥ 0.7 → CONFIRMED → 进正式统计
  │     → confidence < 0.7 → FALSE_POSITIVE → 不进统计

```

> **注意：severity 别名的自动修正。** review LLM 输出的 severity 是 "P0"/"P1"/"P2"/"P3"/"P4" 字符串，需要在代码中映射到 `FindingSeverity` 枚举（"P0"→BLOCKER, "P1"→HIGH, "P2"→MEDIUM, "P3"→LOW, "P4"→INFO）。

**双 LLM 分别用于两条路径：**

| 路径 | 第一个 LLM | 第二个 LLM（review LLM） |
|------|-----------|----------------------|
| 代码审查 Finding 验证 | OCR/审查 LLM 产出 Finding（初始 confidence 0.75） | review LLM 复核 Finding，输出 severity/category/confidence/trigger/suggestedFix |
| 效率分析 Bug 归因验证 | 分类 LLM 判定 commit 为"Bug修复" + git blame 追溯（初始 confidence） | review LLM 复核 blame 追溯结果是否确实代表一个 bug |

**和单 LLM 直接判定的区别：**

| | 单 LLM 直接判定 | 双 LLM 交叉验证 |
|---|---|---|
| 判断方式 | 一个 LLM 说了算 | 两个 LLM 独立打分，取平均值 |
| 准确率 | 可能误判（把重构当 bug） | 两个 LLM 交叉验证，误判率大幅降低 |
| severity/category | 关键词推断 | review LLM 结构化输出 |
| 核心逻辑 | 相信 AI 单次输出 | 置信度平均后 > 0.7 才认定，防止 AI 幻觉 |

### 6.2 实现交叉验证服务

新建或在现有服务中添加双 LLM 验证逻辑。

**Review LLM Prompt 设计（针对代码审查的 Finding）：**

```
你是代码审查专家。请判断以下代码是否存在问题，并给出你的结构化评估。

代码文件: {file}
代码行: {startLine}-{endLine}

代码片段（evidence）:
{evidence}

初步判定：
- 严重度: {severity}（来自第一轮审查/关键词推断）
- 分类: {category}
- 置信度: {confidence}

Blame 信息（如有）：
- 引入者: {owner}
- 引入 commit: {blameCommitIds}
- commit 信息: {commitMessages}

请判断：
  1. 这段代码是否存在所述缺陷（而不仅仅是代码风格或设计偏好）？
  2. 如果存在缺陷，严重度和分类是否准确？
  3. 这个缺陷的触发条件是什么？应该如何修复？

输出格式（JSON）：
{
  "confidence": 0.85,
  "severity": "P1",
  "category": "NPE",
  "reason": "...",
  "trigger": "...",
  "suggestedFix": "..."
}
```

**Review LLM Prompt 设计（针对 Bug 归因的追溯验证）：**

```
你是代码审查专家。请判断以下 git blame 追溯结果是否确实代表一个 bug，
并给出你的置信度（0.0-1.0）。

Fix commit 信息：
  - 提交信息: "修复登录超时问题"
  - 修改了哪些行: [diff 内容]

Blame 追溯到的原始 commit：
  - 提交信息: "重构登录模块"
  - 作者: 张三

请判断：
  1. 原始代码是否确实存在缺陷（而不仅仅是被重构/优化/改需求）？
  2. 这个缺陷是否确实导致了后续修复所描述的问题？

注意区分：
  - 代码重构（行为不变但结构改变）≠ bug
  - 需求变更（原来逻辑对新需求不适用）≠ bug
  - 第三方依赖升级导致的兼容性修改 ≠ bug

输出格式（JSON）：
{"confidence": 0.8, "reason": "findById 返回 null 后未做检查直接调用 getName，确实存在 NPE 风险"}
```

**Review LLM 配置：**
- 使用与分类 LLM 不同的模型配置（可从 `ai_config` 表读取单独的 provider/model）
- 如果只有一个 LLM 可用，用同一个 LLM 但换一个 system prompt（独立会话，不共享上下文）
- 配置项：

```yaml
ai:
  review:
    provider: ${AI_REVIEW_PROVIDER:openai}    # 独立于分类 LLM
    model: ${AI_REVIEW_MODEL:gpt-4o}
    temperature: 0.0                            # 零温度确保一致性
```

### 6.3 修改 BugDetail — 新增状态字段

路径：`devops-ai-core/src/main/java/com/devops/ai/core/efficiency/model/DeveloperEfficiency.java`（BugDetail 内部类）

新增字段（过渡方案，最终迁移到 Finding）：

```java
// BugDetail 内部类新增
private FindingStatus attributionStatus;  // CONFIRMED / FALSE_POSITIVE
private String reviewConclusion;          // 第二个 LLM 的复核结论
private double reviewerConfidence;        // review LLM 的置信度（独立值，不覆盖原 confidence）

public boolean isConfirmed() {
    return attributionStatus == FindingStatus.CONFIRMED;
}
```

**状态流转：**

```
分类 LLM 判定 "Bug修复" + git blame 追溯成功
  │
  ├─ review LLM 给出置信度，两个 LLM 置信度取平均
  │
  ├─ 平均 confidence ≥ 0.7 → CONFIRMED → 进正式统计
  └─ 平均 confidence < 0.7 → FALSE_POSITIVE → 不进统计
```

> **迁移说明：** BugDetail 的 `attributionStatus` / `reviewConclusion` / `reviewerConfidence` 三个字段是过渡方案。Phase 8 报告上线验证通过后，这些字段的逻辑（双 LLM 验证、状态判断）迁移到 `Finding.status` / `Finding.reviewConclusion` / `Finding.confidence` 上，BugDetail 中的这三个字段作为冗余备份保留一个版本后移除。迁移由本 Phase（Phase 6）的开发者负责记录 TODO，实际移除在 Phase 8 完成后统一清理。

### 6.4 补全证据链

P0/P1 的 Finding 在报告中展示完整三段证据。**review LLM 输出 trigger 和 suggestedFix 后，自动补齐 Finding 的证据链。**

| 字段 | 说明 | 来源 |
|------|------|------|
| `evidence` | 问题所在的代码片段 | OCR 输出 existingCode / 审查 LLM |
| `trigger` | 触发条件 | review LLM 输出，覆盖 OCR 初始值 |
| `suggestedFix` | 修复建议（含代码） | review LLM 输出，覆盖 OCR 初始值 |

**报告输出格式：**

````
### UserService.java

**[P1] 第 42-45 行** 空指针风险 | 置信度 85% | 已确认

> 证据：
> ```java
> User user = userMapper.findById(id);
> return user.getName();
> ```
>
> 触发条件：当传入的 id 在数据库中不存在时，findById 返回 null
>
> 建议修复：
> ```diff
> - User user = userMapper.findById(id);
> - return user.getName();
> + User user = userMapper.findById(id);
> + if (user == null) {
> +     throw new NotFoundException("用户不存在: " + id);
> + }
> + return user.getName();
> ```
````

### 6.5 修改效率分析中的 Bug 聚合逻辑

路径：`devops-ai-core/src/main/java/com/devops/ai/core/efficiency/DeveloperEfficiencyService.java`

```java
aggregateFromBugs():
  Phase 1: 统计每人总提交数
  Phase 2: 按 blame 结果分配 bug 份额（仅 CONFIRMED 记录参与）
           - 对每个 fix commit，经 git blame + AI 验证后
           - confidence ≥ 0.7 的 blame 记录保留
           - confidence < 0.7 的 blame 记录直接丢弃（误报）
           - BugDetail 不按 fixCommitId 去重（不同 fix 修同一个 bug commit 各算独立事件）
           - FixDetail 按 fixCommitId 去重（一个 fix commit 只算一次修复）
  Phase 3: 计算 blameAssociationRatio = bugsConfirmed / totalCommits
```

### 6.6 报告中的体现

**Bug 详情表增加置信度和状态列：**

```
#### 张三（45 次提交，关联 3 个，已确认 2 个）

##### 引入的 Bug
| # | 问题提交 | 时间 | 引入描述 | 修复描述 | 文件 | 份额 | 修复人 | 置信度 | 状态 |
|---|---------|------|---------|---------|------|------|--------|--------|------|
| 1 | bbb222 | 03-10 | 重构登录模块 | 修复登录超时 | LoginSvc | 1.00 | 李四 | 0.80 | 已确认 |
| 2 | ddd444 | 04-02 | 新增订单导出 | 修复金额计算 | OrderSvc | 0.50 | 王五 | 0.75 | 已确认 |
| 3 | eee555 | 04-15 | 新增日志输出 | 修复空指针异常 | LogUtil | - | - | 0.20 | 误报 |

> "误报"表示两个 LLM 置信度平均 < 0.7——blame 找错了，该 bug 并非此 commit 引入，不纳入统计。
>
> 仅"已确认"（双 LLM 置信度平均 ≥ 0.7）的记录计入正式排名。
```

## 涉及文件

| 操作 | 文件 | 路径 |
|------|------|------|
| 新增 | ReviewLlmService.java（或内嵌到现有 Service） | review/ai/ |
| 修改 | DeveloperEfficiency.java（BugDetail） | efficiency/model/ |
| 修改 | DeveloperEfficiencyService.java | efficiency/ |
| 修改 | EfficiencyReportGenerator.java | efficiency/ |
| 修改 | Finding.java | review/model/（确认 evidence/trigger/suggestedFix/reviewConclusion 字段完整，review LLM 填充值） |
| 新增配置 | application.yml（review LLM 配置） | bootstrap/src/main/resources/ |

## 验收标准

- [ ] BugDetail 新增 attributionStatus、reviewConclusion、reviewerConfidence 三个字段
- [ ] Review LLM 使用独立模型配置，与分类 LLM 区分开
- [ ] **Review LLM 结构化输出 severity + category + confidence + reason + trigger + suggestedFix**
- [ ] 双 LLM 置信度取平均值逻辑正确
- [ ] confidence ≥ 0.7 → CONFIRMED，< 0.7 → FALSE_POSITIVE
- [ ] review LLM 输出的 severity/category 正确覆盖 Finding 的关键词推断值
- [ ] 报告只展示 CONFIRMED 记录，FALSE_POSITIVE 不进入统计
- [ ] reviewConclusion 字段正确记录复核理由
- [ ] **P0/P1 问题报告输出包含完整的 evidence / trigger / suggestedFix 三段（由 review LLM 补齐）**
- [ ] 缺少 trigger 的 P0/P1 问题被 FindingVerifier（管线中排在 Phase 6 之后）按规则降级
- [ ] 只有一个 LLM 可用时，用同一 LLM 不同 system prompt + 独立会话（不共享上下文）
- [ ] Bug 引入排名仅基于已确认记录

## 下一阶段依赖

Phase 8（报告结构调整）展示 Bug 详情时包含置信度、状态、复核结论列。使用本阶段产出的 status、confidence、reviewConclusion、reviewer 填充问题处置页。
