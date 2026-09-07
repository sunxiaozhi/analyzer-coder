package com.analyzercoder.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.CodebaseKnowledgeApplication;
import com.analyzercoder.application.indexing.IndexJobProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

/** Exercises real HTTP, authentication, CSRF, managed files and PostgreSQL without mocks. */
@EnabledIfEnvironmentVariable(named = "APP_RUN_POSTGRES_IT", matches = "true")
@SpringBootTest(classes = CodebaseKnowledgeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.workers.enabled=false", "app.security.cookie-secure=false"})
class RepositoryHttpWorkflowIT {
    @Autowired TestRestTemplate http;
    @Autowired IndexJobProcessor processor;

    @Test
    void importsIndexesAnswersAndRestoresEvidenceThroughHttp() throws Exception {
        var login = http.postForEntity("/api/auth/login",
                Map.of("username", System.getenv("APP_INITIAL_ADMIN_USERNAME"),
                        "password", System.getenv("APP_INITIAL_ADMIN_PASSWORD")), JsonNode.class);
        assertThat(login.getStatusCode().is2xxSuccessful()).as("login").isTrue();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0]);
        headers.set("X-CSRF-Token", login.getBody().path("csrfToken").asText());

        if (login.getBody().path("mustChangePassword").asBoolean()) {
            var changed = http.exchange("/api/auth/change-password", HttpMethod.POST,
                    new HttpEntity<>(Map.of("currentPassword", System.getenv("APP_INITIAL_ADMIN_PASSWORD"),
                            "newPassword", "Changed-" + UUID.randomUUID() + "!"), headers), JsonNode.class);
            assertThat(changed.getStatusCode().is2xxSuccessful()).as("first-login password change").isTrue();
            headers.set(HttpHeaders.COOKIE, changed.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0]);
            headers.set("X-CSRF-Token", changed.getBody().path("csrfToken").asText());
        }

        var form = new LinkedMultiValueMap<String, Object>();
        form.add("name", "http-verification-" + UUID.randomUUID());
        form.add("file", new ByteArrayResource(sourceZip()) {
            @Override public String getFilename() { return "workflow.zip"; }
        });
        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.addAll(headers);
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        JsonNode imported = send(HttpMethod.POST, "/api/repository-imports/zip", form, uploadHeaders);
        String repositoryId = imported.path("id").asText();
        assertThat(repositoryId).isNotBlank();
        String base = "/api/repositories/" + repositoryId;
        JsonNode queued = send(HttpMethod.POST, base + "/index", Map.of("type", "FULL"), headers);
        String jobUrl = "/api/index-jobs/" + queued.path("id").asText();
        JsonNode status = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            processor.processNextQueuedJob();
            status = send(HttpMethod.GET, jobUrl, null, headers);
            if ("SUCCEEDED".equals(status.path("status").asText())
                    || "FAILED".equals(status.path("status").asText())) break;
        }
        assertThat(status.path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode search = send(HttpMethod.GET, base + "/hybrid-search?query=OrderCheckoutWorkflow", null, headers);
        assertThat(search.path("hits").get(0).path("filePath").asText())
                .isEqualTo("src/OrderCheckoutWorkflow.java");
        JsonNode source = send(HttpMethod.GET, base + "/files/content?path=src/OrderCheckoutWorkflow.java", null, headers);
        assertThat(source.path("content").asText()).contains("class OrderCheckoutWorkflow");
        JsonNode answer = send(HttpMethod.POST, base + "/ask",
                Map.of("question", "OrderCheckoutWorkflow在哪里定义？",
                        "clientRequestId", UUID.randomUUID().toString()), headers);
        assertThat(answer.path("fallbackReason").asText()).isEqualTo("LOCAL_EVIDENCE_MODE");
        assertThat(answer.path("citations").get(0).path("filePath").asText())
                .isEqualTo("src/OrderCheckoutWorkflow.java");
        assertThat(answer.path("snapshotId").asText()).isEqualTo(imported.path("snapshotId").asText());
        JsonNode history = send(HttpMethod.GET, base + "/qa/records/" + answer.path("threadId").asText(), null, headers);
        assertThat(history.path("turns").size()).isEqualTo(1);
        assertThat(history.path("turns").get(0).path("citations")).isEqualTo(answer.path("citations"));
        assertThat(http.getForEntity(base + "/chunks", JsonNode.class).getStatusCode().value()).isEqualTo(401);
    }

    private JsonNode send(HttpMethod method, String url, Object body, HttpHeaders headers) {
        var response = http.exchange(url, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("%s %s returned %s (%s)", method, url, response.getStatusCode(),
                response.getBody() == null ? "empty body" : response.getBody().path("code").asText()).isTrue();
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static byte[] sourceZip() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("README.md"));
            zip.write("# Checkout demo\nThe checkout workflow validates orders.\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("src/OrderCheckoutWorkflow.java"));
            zip.write(("public class OrderCheckoutWorkflow {\n"
                    + "  public boolean checkout(int quantity) { return quantity > 0; }\n"
                    + "}\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}

