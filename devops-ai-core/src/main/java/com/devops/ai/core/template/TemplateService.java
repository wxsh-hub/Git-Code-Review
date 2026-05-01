package com.devops.ai.core.template;

import java.util.List;

public interface TemplateService {

    Template getTemplate(String name);

    List<Template> getAllTemplates();

    Template saveTemplate(Template template);

    void deleteTemplate(String name);

    Template resetTemplate(String name);

    String renderTemplate(String templateName, java.util.Map<String, Object> context);
}
