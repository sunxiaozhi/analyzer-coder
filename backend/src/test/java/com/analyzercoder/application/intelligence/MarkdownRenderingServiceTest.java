package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarkdownRenderingServiceTest {
    private final MarkdownRenderingService service = new MarkdownRenderingService();

    @Test
    void escapesRawHtmlAndRejectsJavascriptUrls() {
        String html =
                service.render(
                        UUID.randomUUID(), "<script>alert(1)</script>\n[x](javascript:alert(1))");

        assertThat(html).doesNotContain("<script", "javascript:");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void rewritesManagedAttachmentImagesToAuthenticatedApiPath() {
        UUID repositoryId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();

        String html =
                service.render(repositoryId, "![架构图](knowledge-attachment://" + attachmentId + ")");

        assertThat(html)
                .contains(
                        "/api/repositories/"
                                + repositoryId
                                + "/knowledge/attachments/"
                                + attachmentId);
        assertThat(html).contains("<img");
    }
}
