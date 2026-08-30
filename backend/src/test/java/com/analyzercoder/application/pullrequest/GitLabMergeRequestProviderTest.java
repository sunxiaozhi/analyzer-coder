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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitLabMergeRequestProviderTest {
    private HttpServer server;
    private URI base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();

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
    void fetchesPagedDiffsAndCreatesANoteWhenMarkerDoesNotExist() {
        GitLabMergeRequestProvider provider =
                new GitLabMergeRequestProvider(
                        new ObjectMapper().findAndRegisterModules(), HttpClient.newHttpClient());
        PullRequestProvider.Reference reference =
                new PullRequestProvider.Reference(base, "group/app", 9);
        PullRequestProvider.AccessToken token = new PullRequestProvider.AccessToken("gitlab-secret");

        PullRequestProvider.PullRequestSnapshot snapshot = provider.fetch(reference, token);
        PullRequestProvider.CommentResult comment =
                provider.upsertReviewComment(reference, "<!-- marker -->", "<!-- marker -->\nreview", token);

        assertThat(snapshot.title()).isEqualTo("MR title");
        assertThat(snapshot.patch()).contains("diff --git a/src/Old.java b/src/New.java", "rename from src/Old.java");
        assertThat(comment.action()).isEqualTo(PullRequestProvider.CommentAction.CREATED);
        assertThat(comment.commentId()).isEqualTo("81");
        assertThat(requests)
                .containsExactly(
                        "GET /projects/group%2Fapp/merge_requests/9",
                        "GET /projects/group%2Fapp/merge_requests/9/diffs?per_page=100&page=1",
                        "GET /projects/group%2Fapp/merge_requests/9/notes?per_page=100&page=1",
                        "POST /projects/group%2Fapp/merge_requests/9/notes");
        assertThat(tokens).allMatch("gitlab-secret"::equals);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String request = exchange.getRequestMethod() + " " + exchange.getRequestURI();
        requests.add(request);
        tokens.add(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
        String body;
        if (request.equals("GET /projects/group%2Fapp/merge_requests/9")) {
            body =
                    "{\"title\":\"MR title\",\"web_url\":\"https://gitlab/mr/9\",\"author\":{\"username\":\"dev\"},"
                            + "\"diff_refs\":{\"base_sha\":\""
                            + "a".repeat(40)
                            + "\",\"head_sha\":\""
                            + "b".repeat(40)
                            + "\"}}";
        } else if (request.contains("/diffs?")) {
            body =
                    "[{\"old_path\":\"src/Old.java\",\"new_path\":\"src/New.java\",\"renamed_file\":true,"
                            + "\"diff\":\"@@ -1 +1 @@\\n-old\\n+new\\n\"}]";
        } else if (request.contains("/notes?") && exchange.getRequestMethod().equals("GET")) {
            body = "[]";
        } else if (request.endsWith("/notes") && exchange.getRequestMethod().equals("POST")) {
            body = "{\"id\":81}";
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
