package com.analyzercoder.application.intelligence;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Service;

/** 将受控 Markdown 渲染为可展示内容，并清理可能造成脚本注入的危险结构。 */
@Service
public class MarkdownRenderingService {
    private static final Pattern SAFE_URL =
            Pattern.compile(
                    "(?i)(https?://[^\\s\"'<>]+|/api/repositories/[0-9a-f-]{36}/knowledge/attachments/[0-9a-f-]{36})");
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final PolicyFactory policy;

    public MarkdownRenderingService() {
        List<Extension> extensions =
                List.of(TablesExtension.create(), StrikethroughExtension.create());
        parser = Parser.builder().extensions(extensions).build();
        renderer =
                HtmlRenderer.builder()
                        .extensions(extensions)
                        .escapeHtml(true)
                        .sanitizeUrls(true)
                        .build();
        policy =
                new HtmlPolicyBuilder()
                        .allowElements(
                                "p",
                                "br",
                                "hr",
                                "strong",
                                "em",
                                "del",
                                "blockquote",
                                "pre",
                                "code",
                                "ul",
                                "ol",
                                "li",
                                "h1",
                                "h2",
                                "h3",
                                "h4",
                                "h5",
                                "h6",
                                "table",
                                "thead",
                                "tbody",
                                "tr",
                                "th",
                                "td",
                                "a",
                                "img")
                        .allowAttributes("href")
                        .matching(SAFE_URL)
                        .onElements("a")
                        .allowAttributes("src")
                        .matching(SAFE_URL)
                        .onElements("img")
                        .allowAttributes("alt", "title")
                        .onElements("img")
                        .allowAttributes("title")
                        .onElements("a")
                        .requireRelNofollowOnLinks()
                        .toFactory();
    }

    public String render(UUID repositoryId, String markdown) {
        String source =
                markdown == null
                        ? ""
                        : markdown.replace(
                                "knowledge-attachment://",
                                "/api/repositories/" + repositoryId + "/knowledge/attachments/");
        Node document = parser.parse(source);
        return policy.sanitize(renderer.render(document));
    }
}
