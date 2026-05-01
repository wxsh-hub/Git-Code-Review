package com.devops.ai.core.generator;

import com.devops.ai.core.model.Category;
import com.devops.ai.core.model.Commit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;

@Component
public class MarkdownGenerator implements DocumentFormatGenerator {

    private static final Logger log = LoggerFactory.getLogger(MarkdownGenerator.class);

    @Override
    public String getFormat() {
        return "markdown";
    }

    @Override
    public DocumentResult generate(DocumentRequest request) {
        StringBuilder content = new StringBuilder();

        content.append("# ").append(request.getProjectName());
        if (request.getProjectVersion() != null && !request.getProjectVersion().isEmpty()) {
            content.append(" - ").append(request.getProjectVersion());
        }
        content.append(" 更新日志\n\n");

        content.append("## ").append(formatDateRange(request)).append("\n\n");

        content.append("### 构建信息\n");
        content.append("- 项目名称: ").append(request.getProjectName()).append("\n");
        content.append("- 构建分支: ").append(request.getBranch()).append("\n");
        content.append("- 构建时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n");
        content.append("- 提交总数: ").append(request.getTotalCommits()).append("\n");
        if (request.getTotalAuthors() > 0) {
            content.append("- 参与开发者: ").append(request.getTotalAuthors()).append(" 人\n");
        }
        content.append("\n");

        if (request.getCategories() != null) {
            for (Category category : request.getCategories()) {
                if (category.getCommits() != null && !category.getCommits().isEmpty()) {
                    content.append("### ").append(category.getName()).append("\n\n");
                    for (Commit commit : category.getCommits()) {
                        String authorInfo = commit.getAuthorName() != null ? "（作者：" + commit.getAuthorName() + "）" : "";
                        content.append("- ").append(commit.getMessage()).append(authorInfo).append("\n");
                    }
                    content.append("\n");
                }
            }
        }

        if (request.getAnalysisReport() != null && !request.getAnalysisReport().isEmpty()) {
            content.append("\n---\n\n## 更新日志分析报告\n\n").append(request.getAnalysisReport()).append("\n");
        }

        DocumentResult result = new DocumentResult(content.toString(), "markdown");
        result.setCommitCount(request.getTotalCommits());
        return result;
    }

    private String formatDateRange(DocumentRequest request) {
        String since = request.getSince();
        String until = request.getUntil();
        if (since != null && until != null) {
            return formatDateTime(since) + " - " + formatDateTime(until);
        }
        if (since != null) {
            return formatDateTime(since) + " - 至今";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private String formatDateTime(String value) {
        if (value == null) return "";
        String[] fromFormats = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
        for (String fmt : fromFormats) {
            try {
                Date d = new SimpleDateFormat(fmt).parse(value);
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
            } catch (Exception e) {
            }
        }
        return value;
    }
}
