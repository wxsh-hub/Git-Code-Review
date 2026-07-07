package com.devops.ai.core.review.model;

/**
 * 代码问题分类，全系统统一。
 *
 * <p>Phase 1 数据地基 — 后续 Phase 直接引用此枚举。</p>
 */
public enum FindingCategory {

    SECURITY("安全漏洞", "SQL注入/XSS/权限绕过等"),
    NPE("空指针风险", "潜在 NullPointerException"),
    TRANSACTION("事务边界缺失", "@Transactional 遗漏或误用"),
    CONCURRENCY("并发安全问题", "竞态条件、线程安全"),
    RESOURCE_LEAK("资源泄漏", "连接/流/文件句柄未释放"),
    ERROR_HANDLING("异常处理不当", "吞异常、空catch、错误信息丢失"),
    SECRET_EXPOSURE("敏感信息暴露", "密码/Token/密钥硬编码"),
    CODE_STYLE("代码风格问题", "命名、格式、注释规范"),
    PERFORMANCE("性能问题", "算法效率、N+1查询、内存占用"),
    DEPENDENCY("依赖风险", "过期/漏洞/SNAPSHOT版本"),
    ARCHITECTURE("架构问题", "循环依赖/分层违规"),
    LOGIC_ERROR("逻辑错误", "业务逻辑缺陷"),
    HARDCODED("硬编码", "魔法数字、写死的配置"),
    OTHER("其他", "暂未归类的问题");

    private final String label;
    private final String description;

    FindingCategory(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() { return label; }
    public String getDescription() { return description; }

    /**
     * 从问题描述文本推断分类。
     * <p>Phase 2 会通过 LLM 结构化输出直接提供精确分类，届时此方法仅作为 fallback。</p>
     */
    public static FindingCategory fromDescription(String text) {
        if (text == null) return OTHER;
        String lower = text.toLowerCase();
        // SECRET_EXPOSURE 优先级最高 — 任何涉及密码/token/密钥/敏感信息的内容
        // 都优先归类为敏感信息暴露，避免被 "权限""硬编码"等关键词抢先匹配
        if (lower.contains("密码") || lower.contains("password") || lower.contains("token")
                || lower.contains("密钥") || lower.contains("secret") || lower.contains("apikey")
                || lower.contains("api_key") || lower.contains("access_key") || lower.contains("敏感")
                || lower.contains("凭据") || lower.contains("credential") || lower.contains("明文")
                || lower.contains("泄露") || lower.contains("expose") || lower.contains("硬编码密钥")) {
            return SECRET_EXPOSURE;
        }
        if (lower.contains("null") || lower.contains("空指针") || lower.contains("npe")
                || lower.contains("nullpointer")) {
            return NPE;
        }
        if (lower.contains("事务") || lower.contains("transaction") || lower.contains("@transactional")) {
            return TRANSACTION;
        }
        if (lower.contains("并发") || lower.contains("concurrent") || lower.contains("线程") || lower.contains("thread")
                || lower.contains("同步") || lower.contains("synchronize")
                || lower.contains("deadlock") || lower.contains("livelock")
                || lower.contains("race") || lower.contains("竞态") || lower.contains("互斥")) {
            return CONCURRENCY;
        }
        if (lower.contains("泄漏") || lower.contains("leak") || lower.contains("关闭") || lower.contains("close")
                || lower.contains("资源") || lower.contains("resource") || lower.contains("stream")
                || lower.contains("connection")) {
            return RESOURCE_LEAK;
        }
        if (lower.contains("异常") || lower.contains("exception") || lower.contains("error")
                || lower.contains("catch") || lower.contains("抛出")) {
            return ERROR_HANDLING;
        }
        if (lower.contains("命名") || lower.contains("格式") || lower.contains("风格") || lower.contains("style")
                || lower.contains("注释") || lower.contains("comment") || lower.contains("规范")
                || lower.contains("convention")) {
            return CODE_STYLE;
        }
        if (lower.contains("性能") || lower.contains("performance") || lower.contains("慢") || lower.contains("slow")
                || lower.contains("n+1") || lower.contains("查询") || lower.contains("内存")) {
            return PERFORMANCE;
        }
        if (lower.contains("依赖") || lower.contains("dependency") || lower.contains("过期")
                || lower.contains("漏洞") || lower.contains("cve") || lower.contains("snapshot")
                || lower.contains("版本")) {
            return DEPENDENCY;
        }
        if (lower.contains("架构") || lower.contains("循环") || lower.contains("circular")
                || lower.contains("分层") || lower.contains("layer")) {
            return ARCHITECTURE;
        }
        if (lower.contains("错误") || lower.contains("逻辑") || lower.contains("bug") || lower.contains("缺陷")
                || lower.contains("不正确") || lower.contains("incorrect")) {
            return LOGIC_ERROR;
        }
        if (lower.contains("硬编码") || lower.contains("hardcode") || lower.contains("魔法")
                || lower.contains("magic number") || lower.contains("写死")) {
            return HARDCODED;
        }
        return OTHER;
    }

    /**
     * Phase 6 — 从 review LLM 输出的 category 代码（如 "NPE"）映射到枚举。
     * 支持枚举名和中文标签两种输入。
     */
    public static FindingCategory fromCode(String code) {
        if (code == null || code.trim().isEmpty()) return OTHER;
        String c = code.trim().toUpperCase();
        try {
            return valueOf(c);
        } catch (IllegalArgumentException e) {
            // 不是枚举名，尝试用中文标签匹配
            for (FindingCategory cat : values()) {
                if (cat.label.equals(code.trim()) || cat.name().equalsIgnoreCase(c)) {
                    return cat;
                }
            }
            return OTHER;
        }
    }
}
