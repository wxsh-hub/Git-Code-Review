package com.devops.ai.core.commit;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class CommitCleaner {

    private static final Pattern[] CLEANUP_PATTERNS = {
            Pattern.compile("--user=[^\\s]+\\s*"),
            Pattern.compile("‘--user=*"),
            Pattern.compile("fix:\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("fix：\\s*"),
            Pattern.compile("refactor:\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("feat:\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("--user="),
            Pattern.compile("update:\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(feat|fix|refactor|docs|update|perf|test|build|ci|style|deps)\\([^)]+\\)\\s*[:：]\\s*"),
            Pattern.compile("\\[Feature\\]\\s*"),
            Pattern.compile("【[^】]+】\\s*"),
            Pattern.compile("(?i)add\\s+"),
    };

    public String sanitize(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return rawMessage;
        }

        String cleaned = rawMessage;
        for (Pattern pattern : CLEANUP_PATTERNS) {
            cleaned = pattern.matcher(cleaned).replaceAll("");
        }

        cleaned = cleaned.trim();
        return cleaned;
    }
}
