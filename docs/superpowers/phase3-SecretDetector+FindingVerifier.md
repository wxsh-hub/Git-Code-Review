# Phase 3: SecretDetector + FindingVerifier

## 目标

在审查完成后、报告生成前，对 Finding 列表做两道把关：敏感信息脱敏和二次校验（行号准确性、去重、误报检测、触发条件完整性）。

## 背景

当前系统存在两个安全/质量缺口：
1. 如果源代码含 `spring.datasource.password=MyP@ssw0rd`，报告里原样展示密码
2. AI/OCR 的原始输出没有校验——报告的"第 500 行有问题"可能文件只有 200 行，同个空指针问题被三条不同建议描述，try-catch 保护的代码被误报为"未处理异常"

## 与 Phase 1 的关系

Phase 1 的 `Finding.sanitize()` 已提供基础的 8 种正则脱敏（password / secret / apiKey / token / privateKey / PEM / JDBC URL / Authorization）。**SecretDetector 在此基础上更进一步：**

| 能力 | Phase 1 sanitize() | Phase 3 SecretDetector |
|------|-------------------|----------------------|
| 脱敏文本字段 | ✅ evidence/suggestedFix/trigger/reviewConclusion | ✅ 同样覆盖 |
| 检测规则数 | 8 种 | 11 种（更细粒度） |
| 每条规则独立开关 | ❌ | ✅ |
| 命中后新增 SECRET_EXPOSURE Finding | ❌ | ✅ 自动创建新 Finding |
| 记录命中规则名+位置 | ❌ | ✅ SecretMatch 结构化记录 |
| 对原 Finding 做脱敏提示 | ❌ | ✅ 注入提示文本 |

**实现策略：** Phase 3 实现 SecretDetector 时，`detectAndSanitize()` 内部调用 `Finding.sanitize()` 做基础脱敏，再在此基础上做增强检测和新 Finding 创建。

## 管线位置（关键）

SecretDetector 和 FindingVerifier 在审查后处理管线中的位置：

```
审查后处理管线（Phase 4 搭建框架，各 Phase 填充）

  原始 Finding 列表（OCR/AI 产出）
    │
    ├─ FindingBlameTracer（Phase 5，仅 P0/P1）
    │    填充 owner / ownerEmail / blameCommitIds / candidateHandler
    │
    ├─ review LLM 交叉验证（Phase 6）
    │    双 LLM 独立判定 → 更新 confidence + status + severity + category
    │    ↑ 从此处开始，confidence 是双 LLM 平均值，不再是关键词猜测
    │
    ├─ 【SecretDetector.detectAndSanitize()】 ← 本 Phase
    │    所有 Finding 文本字段脱敏
    │    新增 SECRET_EXPOSURE 类型的 Finding
    │    ↑ 必须在 review LLM 之后——review LLM 需要看到完整原文做判断
    │
    ├─ 【FindingVerifier.verify()】 ← 本 Phase
    │    行号校验 → 去重 → 误报检测 → 触发条件完整性
    │    ↑ 使用 Phase 6 输出的最终 confidence 做判定
    │
    └─ 生成报告
```

> **为什么 FindingVerifier 排在 Phase 6 之后？** 误报检测（校验 3）依赖 `confidence < 0.8` 判定，Phase 6 之前 confidence 只是关键词猜测值，Phase 6 之后才是双 LLM 交叉验证的最终值。Phase 3 单元测试时可用初始 confidence 代替。

## 要做的事情

### 3.1 新建 `SecretDetector.java`

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/ai/SecretDetector.java`

在审查完成后、报告生成前，对 Finding 列表中所有文本字段做敏感信息扫描和脱敏。

**11 条检测规则：**

| 规则名 | 正则匹配目标 | 示例 |
|--------|-------------|------|
| `jdbc_password` | JDBC URL 中 `password=` 参数 | `jdbc:mysql://...?password=xxx` |
| `spring_datasource_password` | `spring.datasource.password:` 明文 | `spring.datasource.password: MyP@ss` |
| `db_password_env` | `DB_PASSWORD=` 环境变量 | `DB_PASSWORD=secret123` |
| `nacos_password` | `nacos.password:` 配置 | `nacos.password: admin123` |
| `config_password` | `config.password:` 通用配置 | `config.password: p@ssw0rd` |
| `token_exposure` | 长字符串 token 赋值 | `token = "eyJhbGciOiJIUzI..."` |
| `api_key` | API 密钥变量赋值 | `apiKey = "sk-abc123..."` |
| `secret_key` | Secret 密钥变量赋值 | `secretKey = "abc123..."` |
| `access_key` | Access 密钥变量赋值 | `accessKey = "AKIA..."` |
| `plaintext_password` | 通用 `password="xxx"` 写法 | `password="admin123"` |
| `private_key` | PEM 私钥内容 | `-----BEGIN PRIVATE KEY-----` |

**处理逻辑：**

```
SecretDetector.detectAndSanitize(List<Finding> findings):
  for each finding:
    1. 先调用 finding.sanitize()（Phase 1 的 8 种基础脱敏）
    2. 对 evidence、trigger、suggestedFix 三个文本字段逐一做 11 条规则扫描
    3. 命中规则 → 记录命中规则名 + 位置
    4. 将凭据值替换为 ***
    5. 自动新增一条 category=SECRET_EXPOSURE、severity=BLOCKER(P0) 的 Finding
       （如果原 Finding 本身不是安全问题的话）
    6. 在命中规则的地方展示脱敏提示（不改原 Finding 的 severity/category）
```

**报告中的体现（脱敏后）：**

```
**[P0] 第 15 行** 敏感信息泄露

疑似明文凭据，已命中 secret 规则（spring_datasource_password）

触发：配置文件包含明文数据库密码
建议：将凭据移至环境变量或 Nacos Config，通过 ${DB_PASSWORD} 引用
```

**核心方法：**
- `List<Finding> detectAndSanitize(List<Finding>)` — 主入口
- `String sanitize(String text)` — 对单段文本做正则替换
- `List<SecretMatch> detect(String text)` — 返回命中的规则列表（不修改原文）

### 3.2 新建 `FindingVerifier.java`

路径：`devops-ai-core/src/main/java/com/devops/ai/core/review/ai/FindingVerifier.java`

第一轮审查结果必须通过四道校验才能进最终报告。

**四道校验：**

```
原始 Finding 列表
  │
  ├─ 校验 1: 行号准确性
  │    规则：
  │      startLine <= 0? → 拒绝该 Finding（记录日志）
  │      endLine < startLine? → 自动修正 endLine = startLine
  │      startLine > 文件总行数? → 拒绝该 Finding
  │    实现：通过读取目标文件获取实际行数
  │
  ├─ 校验 2: 去重
  │    规则：
  │      同文件 (file) + 行号范围重叠 → 合并为一个
  │      合并时保留较严重的 severity
  │      合并 evidence / suggestedFix 文本（去重拼接）
  │      合并后 confidence 取最大值
  │    重叠判断：两个 Finding 的 [startLine, endLine] 区间有交集
  │
  ├─ 校验 3: 误报检测
  │    规则：
  │      evidence 中已包含防护模式（try-catch / @Valid / if-null-throw / Optional.orElse）
  │      且 confidence < 0.8?
  │      → severity 降级为 LOW(P3)，status 标记 FALSE_POSITIVE
  │    confidence 取值说明：
  │      FindingVerifier 在管线中位于 review LLM（Phase 6）之后运行，
  │      此时 Finding.confidence 已是双 LLM 交叉验证后的最终值。
  │      校验 3 使用此最终值进行判定。
  │      Phase 3 单元测试时（review LLM 尚未接入），直接使用 Finding 的初始 confidence 即可。
  │    检测的防护模式列表：
  │      - try { ... } catch
  │      - @Valid / @Validated
  │      - if (xxx == null) throw / if (xxx != null) { ... } else { throw }
  │      - Optional.ofNullable().orElseThrow()
  │      - Objects.requireNonNull()
  │
  ├─ 校验 4: 触发条件完整性
  │    规则：
  │      P0/P1 问题缺少 trigger 字段（null 或空字符串）?
  │      → severity 降级为 MEDIUM(P2)，不进高危列表
  │    目的：确保高危问题都有可复现的场景描述
  │
  └─ 输出：FilterResult { accepted, rejected, downgraded }
```

**核心方法：**
- `FilterResult verify(List<Finding>)` — 主入口，执行全部四道校验
- `boolean isLineValid(Finding, FileInfo)` — 校验 1
- `List<Finding> deduplicate(List<Finding>)` — 校验 2
- `Finding checkFalsePositive(Finding)` — 校验 3
- `Finding checkTriggerCompleteness(Finding)` — 校验 4

## 涉及文件

| 操作 | 文件 | 路径 |
|------|------|------|
| 新增 | SecretDetector.java | review/ai/ |
| 新增 | FindingVerifier.java | review/ai/ |

## 验收标准

**SecretDetector:**
- [ ] 11 条规则全部实现，每条可独立开关
- [ ] `sanitize()` 将凭据值替换为 `***`，不展示原文
- [ ] 命中规则后自动新增 SECRET_EXPOSURE 类型的 Finding（severity=P0）
- [ ] 对空字符串/null 不抛异常
- [ ] 内部调用 `Finding.sanitize()` 复用 Phase 1 的 8 种基础脱敏

**FindingVerifier:**
- [ ] 行号越界（≤0 或 >文件行数）的 Finding 被正确拒绝
- [ ] endLine < startLine 的自动修正为 endLine = startLine
- [ ] 同文件行号重叠的去重合并正确，severity 取较严重的
- [ ] 误报检测识别 5 种防护模式并正确降级
- [ ] 校验 3 使用的是 Phase 6 交叉验证后的最终 confidence（管线中排在 Phase 6 之后），Phase 3 单元测试时可用初始 confidence 代替
- [ ] P0/P1 缺少 trigger 的正确降级为 MEDIUM(P2)
- [ ] FilterResult 正确区分 accepted / rejected / downgraded

## 下一阶段依赖

Phase 4（审查流水线改造）将 SecretDetector 和 FindingVerifier 以 `FindingPostProcessor` 的方式注入后处理管线。
