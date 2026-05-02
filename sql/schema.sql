-- devops-ai 数据库建表脚本 (MySQL)
-- 如果使用H2内存数据库，JPA会自动建表，此脚本仅供MySQL手动初始化使用

CREATE DATABASE IF NOT EXISTS devops_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE devops_ai;

-- 项目配置表（包含 GitLab 连接信息和项目配置）
CREATE TABLE IF NOT EXISTS project_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '项目名称',
    project_code VARCHAR(100) NOT NULL COMMENT '项目编码(唯一标识,用于第三方对接)',
    project_id VARCHAR(100) DEFAULT NULL COMMENT 'GitLab项目ID',
    default_branch VARCHAR(100) DEFAULT NULL COMMENT '默认分支',
    template_name VARCHAR(100) DEFAULT NULL COMMENT '使用的模板',
    gitlab_url VARCHAR(255) NOT NULL COMMENT 'GitLab地址',
    auth_type VARCHAR(20) NOT NULL COMMENT '认证类型: token/password',
    connect_mode VARCHAR(20) DEFAULT 'api' COMMENT '连接方式: api/clone',
    credentials VARCHAR(500) NOT NULL COMMENT '凭据(加密存储)',
    api_version VARCHAR(10) NOT NULL DEFAULT 'v4' COMMENT 'API版本',
    is_active TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_project_code (project_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目配置表(GitLab连接+项目信息)';

-- AI 配置表
CREATE TABLE IF NOT EXISTS ai_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL COMMENT '配置键',
    config_value VARCHAR(2000) NOT NULL COMMENT '配置值(加密存储)',
    description VARCHAR(255) DEFAULT NULL COMMENT '配置描述',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI配置表';

-- 版本追踪表
CREATE TABLE IF NOT EXISTS version_tracker (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id VARCHAR(100) NOT NULL COMMENT '项目ID',
    branch VARCHAR(100) NOT NULL COMMENT '分支名称',
    last_hash VARCHAR(100) NOT NULL COMMENT '上次生成的最后Hash',
    last_generated DATETIME DEFAULT NULL COMMENT '上次生成时间',
    history_json TEXT DEFAULT NULL COMMENT '生成历史JSON',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_project_branch (project_id, branch)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本追踪表';

-- 生成记录表
CREATE TABLE IF NOT EXISTS generation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id VARCHAR(100) NOT NULL COMMENT '项目ID',
    task_id VARCHAR(100) NOT NULL COMMENT '任务ID',
    status VARCHAR(20) NOT NULL COMMENT '状态: pending/running/completed/failed',
    format VARCHAR(20) NOT NULL COMMENT '输出格式',
    dimension VARCHAR(100) NOT NULL COMMENT '生成维度',
    is_incremental TINYINT(1) DEFAULT 0 COMMENT '是否增量',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    completed_at DATETIME DEFAULT NULL COMMENT '完成时间',
    output_path VARCHAR(500) DEFAULT NULL COMMENT '输出路径',
    error_message TEXT DEFAULT NULL COMMENT '错误信息',
    UNIQUE KEY uk_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成记录表';

-- 分类规则表
CREATE TABLE IF NOT EXISTS commit_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_name VARCHAR(50) NOT NULL COMMENT '分类名称',
    prefix_patterns VARCHAR(500) DEFAULT NULL COMMENT '前缀匹配模式',
    keyword_patterns VARCHAR(500) DEFAULT NULL COMMENT '关键词匹配模式',
    priority INT DEFAULT 0 COMMENT '匹配优先级',
    is_ai_enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用AI辅助'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分类规则表';

-- API 鉴权Token表
CREATE TABLE IF NOT EXISTS api_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token VARCHAR(100) NOT NULL COMMENT 'Token值',
    description VARCHAR(255) DEFAULT NULL COMMENT '描述',
    is_active TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    expires_at DATETIME DEFAULT NULL COMMENT '过期时间',
    UNIQUE KEY uk_token (token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API鉴权Token表';

-- 插入默认分类规则
INSERT INTO commit_category (category_name, prefix_patterns, keyword_patterns, priority, is_ai_enabled) VALUES
('新功能', 'feat:,feat(,新功能:,新增:,[Feature],【新功能】', '新增,添加,实现,开发,功能', 1, 1),
('功能更新', 'update:,update(,功能更新:,更新:,修改:,[update],【更新】', '更新,修改,变更', 2, 1),
('Bug修复', 'fix:,fix(,修复:,bug:,bug(,【修复】', '修复,解决,修正,去除', 3, 1),
('代码重构', 'refactor:,refactor(,重构:,【重构】', '重构,优化结构,调整架构,代码优化', 4, 1),
('文档更新', 'docs:,docs(,文档:,【文档】', '文档,说明,注释,README', 5, 1),
('性能优化', 'perf:,perf(,性能:,【性能】', '性能,优化性能,效率,优化', 6, 1),
('测试相关', 'test:,test(,测试:,【测试】', '测试,单元测试,集成测试', 7, 1),
('构建相关', 'build:,build(,构建:,【构建】', '构建,打包', 8, 1),
('CI配置', 'ci:,ci(,持续集成:,【CI】', 'CI,流水线,Jenkins', 9, 1),
('样式调整', 'style:,style(,样式:,【样式】', 'UI,样式,界面,布局', 10, 1),
('依赖更新', 'deps:,deps(,依赖:,【依赖】', '依赖,升级,更新组件,库', 11, 1);
