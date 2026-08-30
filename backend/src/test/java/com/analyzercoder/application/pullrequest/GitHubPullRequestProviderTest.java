package com.analyzercoder.application.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubPullRequestProviderTest {
    private HttpServer server;
    private URI base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void fetchesMetadataAndRawDiffThenUpdatesTheMarkedIssueComment() {
        GitHubPullRequestProvider provider =
                new GitHubPullRequestProvider(
                        new ObjectMapper().findAndRegisterModules(), HttpClient.newHttpClient());
        PullRequestProvider.Reference reference =
                new PullRequestProvider.Reference(base, "acme/app", 7);
        PullRequestProvider.AccessToken token = new PullRequestProvider.AccessToken("secret-token");

        PullRequestProvider.PullRequestSnapshot snapshot = provider.fetch(reference, token);
        PullRequestProvider.CommentResult comment =
                provider.upsertReviewComment(reference, "<!-- marker -->", "<!-- marker -->\nreview", token);

        assertThat(snapshot.title()).isEqualTo("Safer refund");
        assertThat(snapshot.baseSha()).isEqualTo("a".repeat(40));
        assertThat(snapshot.headSha()).isEqualTo("b".repeat(40));
        assertThat(snapshot.partial()).isFalse();
        assertThat(comment.action()).isEqualTo(PullRequestProvider.CommentAction.UPDATED);
        assertThat(comment.commentId()).isEqualTo("55");
        assertThat(requests)
                .containsExactly(
                        "GET /repos/acme/app/pulls/7",
                        "GET /repos/acme/app/pulls/7",
                        "GET /repos/acme/app/issues/7/comments?per_page=100&page=1",
                        "PATCH /repos/acme/app/issues/comments/55");
        assertThat(new ArrayList<>(authorizations)).allMatch("Bearer secret-token"::equals);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String request = exchange.getRequestMethod() + " " + exchange.getRequestURI();
        requests.add(request);
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        String body;
        String contentType;
        if (request.equals("GET /repos/acme/app/pulls/7")
                && "application/vnd.github.diff".equals(accept)) {
            body =
                    "diff --git a/src/App.java b/src/App.java\n--- a/src/App.java\n+++ b/src/App.java\n@@ -1 +1 @@\n-old\n+new\n";
            contentType = "text/plain";
        } else if (request.equals("GET /repos/acme/app/pulls/7")) {
            body =
                    "{\"title\":\"Safer refund\",\"html_url\":\"https://github.com/acme/app/pull/7\","
                            + "\"changed_files\":1,\"draft\":false,\"user\":{\"login\":\"dev\"},"
                            + "\"base\":{\"sha\":\""
                            + "a".repeat(40)
                            + "\"},\"head\":{\"sha\":\""
                            + "b".repeat(40)
                            + "\"}}";
            contentType = "application/json";
        } else if (request.startsWith("GET /repos/acme/app/issues/7/comments")) {
            body = "[{\"id\":55,\"body\":\"existing <!-- marker -->\",\"html_url\":\"https://github/comment/55\"}]";
            contentType = "application/json";
        } else if (request.equals("PATCH /repos/acme/app/issues/comments/55")) {
            body = "{\"id\":55,\"html_url\":\"https://github/comment/55\"}";
            contentType = "application/json";
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
