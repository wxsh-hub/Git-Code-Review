package com.devops.ai.core.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TemplateServiceImpl implements TemplateService {

    private static final Logger log = LoggerFactory.getLogger(TemplateServiceImpl.class);

    private final Map<String, Template> templateStore = new ConcurrentHashMap<>();

    private static final String STANDARD_CONTENT = "# ${projectName} - ${projectVersion} 更新日志\n" +
            "\n" +
            "## ${dateRange}\n" +
            "\n" +
            "### 构建信息\n" +
            "- 项目名称: ${projectName}\n" +
            "- 构建分支: ${branch}\n" +
            "- 构建时间: ${buildTime}\n" +
            "\n" +
            "${categories}";

    private static final String COMPACT_CONTENT = "# ${projectName} 更新日志\n" +
            "\n" +
            "${categories}";

    private static final String DETAILED_CONTENT = "# ${projectName} - ${projectVersion} 更新日志\n" +
            "\n" +
            "## ${dateRange}\n" +
            "\n" +
            "### 构建信息\n" +
            "- 项目名称: ${projectName}\n" +
            "- 构建分支: ${branch}\n" +
            "- 构建时间: ${buildTime}\n" +
            "- 提交总数: ${totalCommits}\n" +
            "- 参与开发者: ${totalAuthors}\n" +
            "\n" +
            "### 变更分类详情\n" +
            "\n" +
            "${categories}\n" +
            "\n" +
            "---\n" +
            "*由 devops-ai 自动生成*";

    private static final Map<String, String> BUILT_IN_CONTENTS = new LinkedHashMap<>();

    static {
        BUILT_IN_CONTENTS.put("standard", STANDARD_CONTENT);
        BUILT_IN_CONTENTS.put("compact", COMPACT_CONTENT);
        BUILT_IN_CONTENTS.put("detailed", DETAILED_CONTENT);
    }

    public TemplateServiceImpl() {
        initBuiltInTemplates();
    }

    private void initBuiltInTemplates() {
        Template standard = new Template(
                BuiltInTemplate.STANDARD.getName(),
                BuiltInTemplate.STANDARD.getDisplayName(),
                BuiltInTemplate.STANDARD.getDescription(),
                STANDARD_CONTENT,
                true
        );
        templateStore.put(standard.getName(), standard);

        Template compact = new Template(
                BuiltInTemplate.COMPACT.getName(),
                BuiltInTemplate.COMPACT.getDisplayName(),
                BuiltInTemplate.COMPACT.getDescription(),
                COMPACT_CONTENT,
                true
        );
        templateStore.put(compact.getName(), compact);

        Template detailed = new Template(
                BuiltInTemplate.DETAILED.getName(),
                BuiltInTemplate.DETAILED.getDisplayName(),
                BuiltInTemplate.DETAILED.getDescription(),
                DETAILED_CONTENT,
                true
        );
        templateStore.put(detailed.getName(), detailed);
    }

    @Override
    public Template getTemplate(String name) {
        Template template = templateStore.get(name);
        if (template == null) {
            log.warn("Template '{}' not found, falling back to standard", name);
            return templateStore.get(BuiltInTemplate.STANDARD.getName());
        }
        return template;
    }

    @Override
    public List<Template> getAllTemplates() {
        return new ArrayList<>(templateStore.values());
    }

    @Override
    public Template saveTemplate(Template template) {
        templateStore.put(template.getName(), template);
        log.info("Template '{}' saved/updated", template.getName());
        return template;
    }

    @Override
    public void deleteTemplate(String name) {
        BuiltInTemplate builtIn = null;
        try {
            builtIn = BuiltInTemplate.fromName(name);
        } catch (Exception e) {
            log.debug("'{}' is not a built-in template", name);
        }

        if (builtIn != null) {
            throw new IllegalArgumentException("Cannot delete built-in template: " + name);
        }

        templateStore.remove(name);
        log.info("Template '{}' deleted", name);
    }

    @Override
    public Template resetTemplate(String name) {
        BuiltInTemplate builtIn = BuiltInTemplate.fromName(name);
        String originalContent = BUILT_IN_CONTENTS.get(name);
        if (originalContent == null) {
            throw new IllegalArgumentException("Not a built-in template: " + name);
        }
        Template restored = new Template(
                builtIn.getName(),
                builtIn.getDisplayName(),
                builtIn.getDescription(),
                originalContent,
                true
        );
        templateStore.put(name, restored);
        log.info("Built-in template '{}' restored to original", name);
        return restored;
    }

    @Override
    public String renderTemplate(String templateName, Map<String, Object> context) {
        Template template = getTemplate(templateName);
        return template.render(context);
    }
}
