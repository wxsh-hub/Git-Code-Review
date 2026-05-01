package com.devops.ai.core.classifier;

import com.devops.ai.infrastructure.entity.CommitCategory;
import com.devops.ai.infrastructure.repository.CommitCategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RuleClassifier implements ClassifierStrategy {

    private static final Logger log = LoggerFactory.getLogger(RuleClassifier.class);

    private final CommitCategoryRepository categoryRepository;

    private static final List<RuleCategory> DEFAULT_CATEGORIES = new ArrayList<>();

    static {
        DEFAULT_CATEGORIES.add(new RuleCategory("新功能",
                new String[]{"feat:", "feat(", "新功能:", "新增:", "[Feature]", "【新功能】"},
                new String[]{"新增", "添加", "实现", "开发", "功能"}));
        DEFAULT_CATEGORIES.add(new RuleCategory("功能更新",
                new String[]{"update:", "update(", "功能更新:", "更新:", "修改:", "[update]", "【更新】"},
                new String[]{"更新", "修改", "变更"}));
        DEFAULT_CATEGORIES.add(new RuleCategory("Bug修复",
                new String[]{"fix:", "fix(", "修复:", "bug:", "bug(", "【修复】"},
                new String[]{"修复", "解决", "修正", "去除"}));
        DEFAULT_CATEGORIES.add(new RuleCategory("代码重构",
                new String[]{"refactor:", "refactor(", "重构:", "【重构】"},
                new String[]{"重构", "优化结构", "调整架构", "代码优化"}));
        DEFAULT_CATEGORIES.add(new RuleCategory("文档更新",
                new String[]{"docs:", "docs(", "文档:", "【文档】"},
                new String[]{"文档", "说明", "注释", "README"}));
        DEFAULT_CATEGORIES.add(new RuleCategory("性能优化",
                new String[]{"perf:", "perf(", "性能:", "【性能】"},
                new String[]{"性能", "优化性能", "效率", "优化"}));
        DEFAULT_CATEGORIES.add(new RuleCategory("测试相关",
                new String[]{"test:", "test(", "测试:", "【测试】"},
                new String[]{"测试", "单元测试", "集成测试"}));
        DEFAULT_CATEGORIES.add(new RuleCategory("构建相关",
                new String[]{"build:", "build(", "构建:", "【构建】"},
                new String[]{"构建", "打包"}));
        DEFAULT_CATEGORIES.add(new RuleCategory("CI配置",
                new String[]{"ci:", "ci(", "持续集成:", "【CI】"},
                new String[]{"CI", "流水线", "Jenkins"}));
        DEFAULT_CATEGORIES.add(new RuleCategory("样式调整",
                new String[]{"style:", "style(", "样式:", "【样式】"},
                new String[]{"UI", "样式", "界面", "布局"}));
        DEFAULT_CATEGORIES.add(new RuleCategory("依赖更新",
                new String[]{"deps:", "deps(", "依赖:", "【依赖】"},
                new String[]{"依赖", "升级", "更新组件", "库"}));
    }

    public RuleClassifier(CommitCategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    public String getStrategyName() {
        return "rule";
    }

    @Override
    public ClassificationResult classify(String message) {
        if (message == null || message.isEmpty()) {
            return new ClassificationResult(null, message, "其他变更");
        }

        String originalMessage = message;

        for (RuleCategory category : DEFAULT_CATEGORIES) {
            if (matchesPrefix(category, originalMessage)) {
                return new ClassificationResult(null, message, category.getName());
            }
        }

        for (RuleCategory category : DEFAULT_CATEGORIES) {
            if (matchesKeyword(category, originalMessage)) {
                return new ClassificationResult(null, message, category.getName());
            }
        }

        return new ClassificationResult(null, message, "其他变更");
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private boolean matchesPrefix(RuleCategory category, String message) {
        for (String prefix : category.getPrefixes()) {
            if (message.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesKeyword(RuleCategory category, String message) {
        for (String keyword : category.getKeywords()) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static class RuleCategory {
        private final String name;
        private final String[] prefixes;
        private final String[] keywords;

        public RuleCategory(String name, String[] prefixes, String[] keywords) {
            this.name = name;
            this.prefixes = prefixes;
            this.keywords = keywords;
        }

        public String getName() {
            return name;
        }

        public String[] getPrefixes() {
            return prefixes;
        }

        public String[] getKeywords() {
            return keywords;
        }
    }
}
