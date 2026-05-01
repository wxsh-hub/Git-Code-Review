package com.devops.ai.core.generator;

import com.devops.ai.core.model.Category;
import com.devops.ai.core.model.Commit;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HtmlGenerator implements DocumentFormatGenerator {

    private static final Logger log = LoggerFactory.getLogger(HtmlGenerator.class);

    private final Parser parser;
    private final HtmlRenderer renderer;

    public HtmlGenerator() {
        this.parser = Parser.builder().build();
        this.renderer = HtmlRenderer.builder().build();
    }

    @Override
    public String getFormat() {
        return "html";
    }

    @Override
    public DocumentResult generate(DocumentRequest request) {
        MarkdownGenerator markdownGenerator = new MarkdownGenerator();
        DocumentResult markdownResult = markdownGenerator.generate(request);

        String markdownContent = markdownResult.getContent();
        Node document = parser.parse(markdownContent);
        String htmlBody = renderer.render(document);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n");
        html.append("<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>").append(request.getProjectName()).append(" 更新日志</title>\n");
        html.append("<style>\n");
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; ");
        html.append("max-width: 900px; margin: 0 auto; padding: 20px; color: #333; line-height: 1.6; }\n");
        html.append("h1 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 10px; }\n");
        html.append("h2 { color: #34495e; margin-top: 30px; }\n");
        html.append("h3 { color: #2980b9; margin-top: 20px; }\n");
        html.append("ul { padding-left: 20px; }\n");
        html.append("li { margin: 5px 0; }\n");
        html.append("</style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append(htmlBody);
        html.append("</body>\n");
        html.append("</html>");

        DocumentResult result = new DocumentResult(html.toString(), "html");
        result.setCommitCount(request.getTotalCommits());
        return result;
    }
}
