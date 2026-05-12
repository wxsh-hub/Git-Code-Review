package com.devops.ai.init;

import com.devops.ai.infrastructure.entity.CommitCategory;
import com.devops.ai.infrastructure.entity.ProjectConfig;
import com.devops.ai.infrastructure.repository.CommitCategoryRepository;
import com.devops.ai.infrastructure.repository.ProjectConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final CommitCategoryRepository categoryRepository;
    private final ProjectConfigRepository projectConfigRepository;

    public DataInitializer(CommitCategoryRepository categoryRepository,
                           ProjectConfigRepository projectConfigRepository) {
        this.categoryRepository = categoryRepository;
        this.projectConfigRepository = projectConfigRepository;
    }

    @Override
    public void run(String... args) {
        initDefaultCategories();
        checkConfigStatus();
    }

    private void initDefaultCategories() {
        if (categoryRepository.count() > 0) {
            return;
        }

        log.info("Initializing default commit categories...");

        saveCategory("新功能", "feat:,feat(,新功能:,新增:,[Feature],【新功能】",
                "新增,添加,实现,开发,功能", 1, true);
        saveCategory("功能更新", "update:,update(,功能更新:,更新:,修改:,[update],【更新】",
                "更新,修改,变更", 2, true);
        saveCategory("Bug修复", "fix:,fix(,修复:,bug:,bug(,【修复】",
                "修复,解决,修正,去除", 3, true);
        saveCategory("代码重构", "refactor:,refactor(,重构:,【重构】",
                "重构,优化结构,调整架构,代码优化", 4, true);
        saveCategory("文档更新", "docs:,docs(,文档:,【文档】",
                "文档,说明,注释,README", 5, true);
        saveCategory("性能优化", "perf:,perf(,性能:,【性能】",
                "性能,优化性能,效率,优化", 6, true);
        saveCategory("测试相关", "test:,test(,测试:,【测试】",
                "测试,单元测试,集成测试", 7, true);
        saveCategory("构建相关", "build:,build(,构建:,【构建】",
                "构建,打包", 8, true);
        saveCategory("CI配置", "ci:,ci(,持续集成:,【CI】",
                "CI,流水线,Jenkins", 9, true);
        saveCategory("样式调整", "style:,style(,样式:,【样式】",
                "UI,样式,界面,布局", 10, true);
        saveCategory("依赖更新", "deps:,deps(,依赖:,【依赖】",
                "依赖,升级,更新组件,库", 11, true);

        log.info("Default categories initialized successfully.");
    }

    private void saveCategory(String name, String prefixes, String keywords, int priority, boolean aiEnabled) {
        CommitCategory category = new CommitCategory();
        category.setCategoryName(name);
        category.setPrefixPatterns(prefixes);
        category.setKeywordPatterns(keywords);
        category.setPriority(priority);
        category.setAiEnabled(aiEnabled);
        categoryRepository.save(category);
    }

    private void checkConfigStatus() {
        List<ProjectConfig> activeConfigs = projectConfigRepository.findByActiveTrue();
        if (activeConfigs.isEmpty()) {
            log.info("No active project configuration found. Please visit /config/project to configure.");
            log.info("Application started successfully. Waiting for project configuration.");
        } else {
            log.info("Found {} active project configuration(s).", activeConfigs.size());
        }
    }
}
