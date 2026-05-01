package com.devops.ai.infrastructure.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogSanitizer {

    private static final Pattern[] SENSITIVE_PATTERNS = {
            Pattern.compile("(?i)(api[_-]?key\\s*[=:]\\s*)[a-z0-9_\\-]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(password\\s*[=:]\\s*)[^\\s,;&'\"]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(token\\s*[=:]\\s*)[a-z0-9_\\-]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(secret\\s*[=:]\\s*)[a-z0-9_\\-]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(credentials\\s*[=:]\\s*)[^\\s,;&'\"]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(authorization\\s*[=:]\\s*)[^\\s,;&'\"]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(PRIVATE-TOKEN\\s*[=:]\\s*)[a-z0-9_\\-]+", Pattern.CASE_INSENSITIVE),
    };

    private LogSanitizer() {
    }

    public static String sanitize(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String sanitized = message;
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            Matcher matcher = pattern.matcher(sanitized);
            if (matcher.find()) {
                sanitized = matcher.replaceAll("$1******");
            }
        }
        return sanitized;
    }
}
