package com.devops.ai.core.review.ai;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 从文件路径提取业务领域名的工具类。
 *
 * <p>Phase 4（业务链路分组）和 Phase 8（模块风险分布）共用此工具，
 * 确保两处的模块划分逻辑一致。</p>
 *
 * <p>规则：从文件路径的最后一个有意义目录名提取模块名，跳过框架目录。</p>
 *
 * <pre>{@code
 *   src/main/java/com/example/user/UserController.java → "user"
 *   src/main/java/com/example/order/OrderService.java   → "order"
 *   application.yml                                     → "other"
 * }</pre>
 */
public class ModulePathResolver {

    /** 框架/基础设施目录名，提取模块名时跳过 */
    private static final Set<String> FRAMEWORK_DIRS = new HashSet<>(Arrays.asList(
            "java", "src", "main", "resources", "test",
            "controller", "service", "mapper", "dao", "repository",
            "model", "entity", "domain", "dto", "vo",
            "config", "util", "utils", "common", "base", "infra"
    ));

    private ModulePathResolver() {
        // 工具类不可实例化
    }

    /**
     * 从文件路径提取业务领域名。
     *
     * <p>算法：将路径按 / 拆分，从右往左找到第一个不在 FRAMEWORK_DIRS 中的目录名。
     * 如果全路径都是框架目录（如 application.yml 在根目录），返回 "other"。</p>
     *
     * @param filePath 文件路径，如 "src/main/java/com/example/user/UserController.java"
     * @return 模块名，如 "user"；无法提取时返回 "other"
     */
    public static String resolveModule(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "other";
        }

        // 去掉文件名，只保留目录部分
        int lastSlash = filePath.lastIndexOf('/');
        if (lastSlash < 0) {
            // 根目录文件（如 application.yml）→ "other"
            return "other";
        }
        String dirPart = filePath.substring(0, lastSlash);

        // 按 / 拆分目录，从右往左找第一个非框架目录
        String[] parts = dirPart.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].toLowerCase();
            if (!part.isEmpty() && !FRAMEWORK_DIRS.contains(part)) {
                return parts[i]; // 返回原始大小写
            }
        }

        return "other";
    }
}
