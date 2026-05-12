package com.devops.ai.core.review.parser;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaFileParser {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^package\\s+([a-zA-Z_][\\w.]*)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^import\\s+(?:static\\s+)?([a-zA-Z_][\\w.*]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern CLASS_PATTERN = Pattern.compile("(?:public\\s+)?(?:abstract\\s+)?(?:final\\s+)?(?:class|interface|enum)\\s+(\\w+)");
    private static final Pattern METHOD_PATTERN = Pattern.compile("(?:public|protected|private|static|final|abstract|synchronized|native)\\s+(?:<[^>]+>\\s+)?(?:[\\w.]+(?:<[^>]+>)?)\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\s*\\{");
    private static final Pattern ANNOTATION_PATTERN = Pattern.compile("@(\\w+)(?:\\([^)]*\\))?");

    private String packageName;
    private List<String> imports = new ArrayList<>();
    private List<String> classNames = new ArrayList<>();
    private List<String> methodNames = new ArrayList<>();
    private List<String> annotations = new ArrayList<>();

    public JavaFileParser(String source) {
        if (source == null || source.trim().isEmpty()) return;
        parse(source);
    }

    private void parse(String source) {
        Matcher pkg = PACKAGE_PATTERN.matcher(source);
        if (pkg.find()) {
            packageName = pkg.group(1);
        }

        Matcher imp = IMPORT_PATTERN.matcher(source);
        while (imp.find()) {
            imports.add(imp.group(1));
        }

        Matcher cls = CLASS_PATTERN.matcher(source);
        while (cls.find()) {
            classNames.add(cls.group(1));
        }

        Matcher method = METHOD_PATTERN.matcher(source);
        while (method.find()) {
            methodNames.add(method.group(1));
        }

        Matcher ann = ANNOTATION_PATTERN.matcher(source);
        while (ann.find()) {
            annotations.add(ann.group(1));
        }
    }

    public String getPackageName() { return packageName; }
    public List<String> getImports() { return imports; }
    public List<String> getClassNames() { return classNames; }
    public List<String> getMethodNames() { return methodNames; }
    public List<String> getAnnotations() { return annotations; }

    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        if (packageName != null) sb.append("package: ").append(packageName).append("\n");
        if (!classNames.isEmpty()) sb.append("classes: ").append(String.join(", ", classNames)).append("\n");
        if (!methodNames.isEmpty()) sb.append("methods: ").append(String.join(", ", methodNames)).append("\n");
        if (!imports.isEmpty()) {
            int depCount = (int) imports.stream().filter(i -> !i.startsWith("java.") && !i.startsWith("javax.")).count();
            sb.append("external dependencies: ").append(depCount).append("\n");
        }
        return sb.toString();
    }

    public static boolean isJavaFile(String filePath) {
        return filePath != null && filePath.endsWith(".java");
    }
}
