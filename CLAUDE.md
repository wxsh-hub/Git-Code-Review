# CLAUDE.md

## 项目概述

devops-ai 是一个 Spring Boot 2.4.2 项目（Java 8），通用项目发布文档生成工具。

## 项目结构

```
devops-ai/
├── pom.xml                      # 父 POM（多模块）
├── devops-ai-api/               # API 接口层
├── devops-ai-core/              # 核心业务逻辑
├── devops-ai-web/               # Web 层（Controller、页面）
├── devops-ai-infrastructure/    # 基础设施层（数据访问）
├── devops-ai-bootstrap/         # 启动模块，包含 application.yml
├── data/                        # H2 数据库文件
├── logs/                        # 日志目录
├── output/                      # 生成文档输出目录
├── start.sh / start.bat         # 启动脚本
└── app.jar                      # 构建产物副本
```

## 启动服务

### ⚠️ 关键：必须从 `devops-ai-bootstrap` 目录启动

数据库连接使用**相对路径** `jdbc:h2:file:../data/devops-ai`，因此启动时必须确保工作目录是 `devops-ai-bootstrap/`，否则相对路径解析错误，会导致数据库连接失败。

**正确方式：**
```bash
cd devops-ai-bootstrap
java -Xms1g -Xmx1g -Dfile.encoding=UTF-8 -jar target/devops-ai-bootstrap-1.0.0.jar
```

或使用启动脚本（脚本内部定位到 devops-ai-bootstrap 目录）：
```bash
bash start.sh console    # 前台启动
bash start.sh start      # 后台启动
```

**错误方式：** 在项目根目录直接运行 `java -jar devops-ai-bootstrap/target/...`

## 服务信息

| 项目 | 值 |
|------|-----|
| 端口 | 8070 |
| H2 控制台 | http://localhost:8070/h2-console |
| Swagger | http://localhost:8070/swagger-ui.html |
| 健康检查 | http://localhost:8070/actuator/health |
| 数据库 | H2 文件模式，URL: `jdbc:h2:file:../data/devops-ai`，用户 `sa`，无密码 |
| 日志 | `logs/devops-ai.log` |
| 构建 | `mvn clean package -DskipTests`，产物在 `devops-ai-bootstrap/target/` |
| 停止服务 | `taskkill /f /im java.exe`（Windows，会终止所有 Java 进程） |

## Git 仓库

| Remote | 地址 | 说明 |
|--------|------|------|
| **github** | `github.com:wxsh-hub/Git-Code-Review.git` | **默认推送目标**，提交代码用这个 |
| origin | `jihulab.com/wxsh/ai-git-review.git` | 极狐 GitLab，不要推到这里 |

```bash
git push github master    # 正确
git push origin master    # 错误，会推送到极狐 GitLab
```

## 重新构建并重启

```bash
# 1. 停止
cmd.exe //c "taskkill /F /IM java.exe /T"
# 2. 构建
cd D:/111/devops-ai && mvn clean package -DskipTests -q
# 3. 启动（注意目录）
cd D:/111/devops-ai/devops-ai-bootstrap
nohup java -Xms1g -Xmx1g -Dfile.encoding=UTF-8 -jar target/devops-ai-bootstrap-1.0.0.jar > ../logs/console.log 2>&1 &
```

## 已知问题与处理

### 增量测试硬编码 Hash（调试用，勿提交到生产）

`GenerationOrchestrator.java` 中有两处临时硬编码，用于 `dig-master` 增量测试时保证每次范围和 10:00 任务一致：

| 位置 | 作用 | 硬编码值 |
|------|------|---------|
| L186-197 `generateSync()` | commit 拉取阶段的 sinceHash | `e49ed8f5` |
| L336-346 审查 diff 范围 | 代码审查 diff 的 sinceHash | `e49ed8f5` |

两处都带 `=== TEMPORARY: 硬编码` 注释和 `[HARDCODED]` 日志标记。**调试通过后需删除这两段硬编码**，恢复正常的 tracker 逻辑。

测试参考任务：`dig-master_20260708-095339`（78 commit，~215 文件 diff）

### 候选处理人 git 命令报错

`FindingBlameTracer.gitLog()` 通过 `ProcessBuilder` 执行 `git log -1` 来追溯代码行的最后修改者。当 git 命令因任何原因失败时（退出码非 0），错误输出 **不能** 作为 handler 值返回给报告——现已通过检查 `process.exitValue()` 修复：退出码非 0 时返回 `null` 并记录 WARN 日志。

如果报告中仍出现 git 错误输出，检查日志中的 `git log failed (exit=...)` 记录，排查：
- repoPath 是否正确指向已克隆的仓库
- 仓库中是否存在相关文件路径
- git 命令本身是否可用（`git --version`）
