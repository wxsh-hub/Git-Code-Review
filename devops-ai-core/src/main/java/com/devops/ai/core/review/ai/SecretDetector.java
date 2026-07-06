package com.devops.ai.core.review.ai;

import com.devops.ai.core.review.model.Finding;
import com.devops.ai.core.review.model.FindingCategory;
import com.devops.ai.core.review.model.FindingSeverity;
import com.devops.ai.core.review.model.SecretMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息检测器，在审查完成后对 Finding 文本字段做 11 条规则扫描和脱敏。
 *
 * <p>处理流程：
 * <ol>
 *   <li>调用 {@link Finding#sanitize()} 做 Phase 1 的 8 种基础脱敏</li>
 *   <li>对 evidence / trigger / suggestedFix 逐一做 11 条规则扫描</li>
 *   <li>命中 → 记录规则名 + 位置，替换凭据值为 ***</li>
 *   <li>自动新增 SECRET_EXPOSURE (P0) 类型的 Finding</li>
 * </ol>
 *
 * <p>每条规则可通过 {@link #disableRule(String)} / {@link #enableRule(String)} 独立开关。</p>
 */
@Component
public class SecretDetector {

    private static final Logger log = LoggerFactory.getLogger(SecretDetector.class);

    // ================================================================
    // 11 条规则定义
    // ================================================================

    private static class Rule {
        final String name;
        final Pattern pattern;
        final String description;
        boolean enabled;

        Rule(String name, String regex, String description) {
            this.name = name;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.description = description;
            this.enabled = true;
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    public SecretDetector() {
        // 1. JDBC URL password= 参数
        addRule("jdbc_password",
                "(jdbc|mysql|postgresql|oracle|sqlserver|mariadb|mongodb)://[^\\s?]*[?&;]password=([^&;\\s]+)",
                "JDBC URL 中包含明文 password 参数");

        // 2. spring.datasource.password 明文
        addRule("spring_datasource_password",
                "spring\\.datasource\\.password\\s*[:=]\\s*(\\S+)",
                "Spring 数据源配置包含明文密码");

        // 3. DB_PASSWORD= 环境变量
        addRule("db_password_env",
                "DB_PASSWORD\\s*=\\s*(\\S+)",
                "DB_PASSWORD 环境变量明文赋值");

        // 4. nacos.password 配置
        addRule("nacos_password",
                "nacos\\.password\\s*[:=]\\s*(\\S+)",
                "Nacos 配置包含明文密码");

        // 5. config.password 通用配置
        addRule("config_password",
                "config\\.password\\s*[:=]\\s*(\\S+)",
                "config.password 通用明文密码配置");

        // 6. 长 token 赋值（JWT / OAuth token）
        addRule("token_exposure",
                "\\btoken\\s*=\\s*\"([A-Za-z0-9_\\-]{40,})\"",
                "代码中硬编码了长 Token");

        // 7. API Key
        addRule("api_key",
                "\\bapi_?key\\s*[:=]\\s*\"([A-Za-z0-9_\\-]{16,})\"",
                "代码中硬编码了 API Key");

        // 8. Secret Key
        addRule("secret_key",
                "\\bsecret_?key\\s*[:=]\\s*\"([A-Za-z0-9_\\-]{16,})\"",
                "代码中硬编码了 Secret Key");

        // 9. Access Key
        addRule("access_key",
                "\\baccess_?key\\s*[:=]\\s*\"([A-Za-z0-9_\\-]{16,})\"",
                "代码中硬编码了 Access Key");

        // 10. 通用 password="xxx" 写法
        addRule("plaintext_password",
                "\\bpassword\\s*=\\s*\"([^\"]+)\"",
                "通用明文密码赋值");

        // 11. PEM 私钥
        addRule("private_key",
                "-----BEGIN\\s*(RSA |EC |DSA |OPENSSH |)PRIVATE KEY-----",
                "代码中包含 PEM 格式私钥");
    }

    private void addRule(String name, String regex, String description) {
        rules.add(new Rule(name, regex, description));
    }

    // ================================================================
    // 规则开关
    // ================================================================

    /** 禁用指定规则（不区分大小写） */
    public void disableRule(String name) {
        for (Rule r : rules) {
            if (r.name.equalsIgnoreCase(name)) {
                r.enabled = false;
                return;
            }
        }
    }

    /** 启用指定规则（不区分大小写） */
    public void enableRule(String name) {
        for (Rule r : rules) {
            if (r.name.equalsIgnoreCase(name)) {
                r.enabled = true;
                return;
            }
        }
    }

    /** 禁用所有规则，然后手动启用需要的（适合最小化检测场景） */
    public void disableAll() {
        for (Rule r : rules) rules.removeIf(rule -> !rule.enabled);  // no — just set all to false
        for (Rule r : rules) r.enabled = false;
    }

    /** 启用所有 11 条规则（默认状态） */
    public void enableAll() {
        for (Rule r : rules) r.enabled = true;
    }

    // ================================================================
    // 主入口
    // ================================================================

    /**
     * 对 Finding 列表执行敏感信息检测和脱敏。
     *
     * <p>返回值是追加的 SECRET_EXPOSURE Finding 列表（不含入参中的 Finding）。
     * 入参中的 Finding 的文本字段已经就地脱敏。</p>
     *
     * @param findings 原始 Finding 列表（会就地修改 evidence/trigger/suggestedFix）
     * @return 新增的 SECRET_EXPOSURE Finding（已去重，每个唯一文件+规则只生成一条）
     */
    public List<Finding> detectAndSanitize(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return Collections.emptyList();
        }

        // 去重：同一 file + ruleName 只生成一条 SECRET_EXPOSURE Finding
        Set<String> emitted = new HashSet<>();
        List<Finding> newFindings = new ArrayList<>();

        for (Finding f : findings) {
            // Step 1: Phase 1 基础脱敏
            f.sanitize();

            // Step 2: 对三个文本字段做 11 条规则增强检测
            List<SecretMatch> allMatches = new ArrayList<>();
            allMatches.addAll(scanField(f.getEvidence(), "evidence"));
            allMatches.addAll(scanField(f.getTrigger(), "trigger"));
            allMatches.addAll(scanField(f.getSuggestedFix(), "suggestedFix"));

            // Step 3: 对文本字段做替换
            if (f.getEvidence() != null) {
                f.setEvidence(sanitize(f.getEvidence()));
            }
            if (f.getTrigger() != null) {
                f.setTrigger(sanitize(f.getTrigger()));
            }
            if (f.getSuggestedFix() != null) {
                f.setSuggestedFix(sanitize(f.getSuggestedFix()));
            }

            // Step 4: 为命中规则创建 SECRET_EXPOSURE Finding
            for (SecretMatch match : allMatches) {
                String dedupKey = (f.getFile() != null ? f.getFile() : "") + "|" + match.getRuleName();
                if (emitted.add(dedupKey)) {
                    Finding secretFinding = createSecretExposure(f, match);
                    newFindings.add(secretFinding);
                    log.info("Secret detected: rule={}, file={}, field={}",
                            match.getRuleName(), f.getFile(), match.getField());
                }
            }
        }

        return newFindings;
    }

    // ================================================================
    // 公开方法
    // ================================================================

    /**
     * 对单段文本执行 11 条规则的扫描并返回命中的规则列表（不修改原文）。
     */
    public List<SecretMatch> detect(String text) {
        return scanField(text, "unknown");
    }

    /**
     * 对单段文本执行 11 条规则的脱敏替换，返回替换后的文本。
     */
    public String sanitize(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = text;
        for (Rule rule : rules) {
            if (!rule.enabled) continue;
            Matcher m = rule.pattern.matcher(result);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String replacement = buildReplacement(m);
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            result = sb.toString();
        }
        return result;
    }

    /** 返回当前启用的规则名列表（调试用） */
    public List<String> activeRules() {
        List<String> names = new ArrayList<>();
        for (Rule r : rules) {
            if (r.enabled) names.add(r.name);
        }
        return names;
    }

    // ================================================================
    // 内部实现
    // ================================================================

    /** 扫描单段文本，返回命中的规则列表 */
    private List<SecretMatch> scanField(String text, String fieldName) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        List<SecretMatch> matches = new ArrayList<>();
        for (Rule rule : rules) {
            if (!rule.enabled) continue;
            Matcher m = rule.pattern.matcher(text);
            if (m.find()) {
                String snippet = m.group();
                matches.add(new SecretMatch(rule.name, snippet, fieldName));
            }
        }
        return matches;
    }

    /** 构建替换文本：保留前缀，凭据值替换为 *** */
    private String buildReplacement(Matcher m) {
        String matched = m.group();
        // 私钥块特殊处理：整段替换
        if (matched.startsWith("-----BEGIN")) {
            return matched.substring(0, matched.indexOf('\n') > 0
                    ? matched.indexOf('\n') : matched.length())
                    + "\n***[PRIVATE KEY REDACTED]***\n-----END PRIVATE KEY-----";
        }

        // URL password 参数: 保留前缀 + ***
        if (matched.contains("password=") && (matched.contains("://") || matched.contains("jdbc:"))) {
            int idx = matched.lastIndexOf("password=") + "password=".length();
            return matched.substring(0, idx) + "***";
        }

        // Key=value / key: value / key="value" 格式
        int eqIdx = matched.indexOf('=');
        int colonIdx = matched.indexOf(':');
        int sepIdx;
        if (eqIdx > 0 && (colonIdx < 0 || eqIdx < colonIdx)) {
            sepIdx = eqIdx;
        } else if (colonIdx > 0) {
            sepIdx = colonIdx;
        } else {
            return "***";
        }

        String prefix = matched.substring(0, sepIdx + 1);
        String value = matched.substring(sepIdx + 1).trim();
        // 去掉引号
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return prefix + " \"***\"";
        }
        return prefix + " ***";
    }

    /** 创建一个新 SECRET_EXPOSURE Finding */
    private Finding createSecretExposure(Finding source, SecretMatch match) {
        Finding f = new Finding();
        f.setFile(source.getFile());
        f.setStartLine(source.getStartLine());
        f.setEndLine(source.getEndLine());
        f.setSeverity(FindingSeverity.BLOCKER);    // P0
        f.setCategory(FindingCategory.SECRET_EXPOSURE);
        f.setConfidence(0.95);                     // 正则匹配高置信度
        f.setStatus(source.getStatus());           // 继承原 Finding 的状态（Phase 6 后会更新）

        String ruleDesc = getRuleDescription(match.getRuleName());
        f.setEvidence("疑似明文凭据，已命中 secret 规则（" + match.getRuleName() + "）");
        f.setTrigger("代码或配置文件中包含" + ruleDesc);
        f.setSuggestedFix("将凭据移至环境变量或配置中心（Nacos/Vault），通过 ${VAR_NAME} 引用");

        return f;
    }

    private String getRuleDescription(String ruleName) {
        for (Rule r : rules) {
            if (r.name.equalsIgnoreCase(ruleName)) return r.description;
        }
        return ruleName;
    }
}
