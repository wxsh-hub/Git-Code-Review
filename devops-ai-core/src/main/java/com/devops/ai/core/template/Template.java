package com.devops.ai.core.template;

import java.util.Map;

public class Template {

    private String name;
    private String displayName;
    private String description;
    private String content;
    private boolean builtIn;

    public Template() {
    }

    public Template(String name, String displayName, String description, String content, boolean builtIn) {
        this.name = name;
        this.displayName = displayName;
        this.description = description;
        this.content = content;
        this.builtIn = builtIn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(boolean builtIn) {
        this.builtIn = builtIn;
    }

    public String render(Map<String, Object> context) {
        if (content == null || context == null) {
            return content;
        }
        String result = content;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }
}
