package com.analyzercoder.application.pullrequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** 提供方 HTTP 调用的超时、无重定向、限量读取和错误脱敏基类。 */
abstract class PullRequestHttpSupport {
    static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    static final int MAX_PATCH_BYTES = UnifiedDiffRepositoryChangeParser.MAX_PATCH_BYTES;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    final ObjectMapper json;
    private final HttpClient http;

    PullRequestHttpSupport(ObjectMapper json) {
        this(
                json,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    PullRequestHttpSupport(ObjectMapper json, HttpClient http) {
        this.json = json;
        this.http = http;
    }

    final Response get(URI uri, Map<String, String> headers, int maximumBytes) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET();
        headers.forEach(builder::header);
        return send(builder.build(), maximumBytes);
    }

    final Response jsonRequest(
            String method, URI uri, Map<String, String> headers, Map<String, ?> payload) {
        try {
            byte[] body = json.writeValueAsBytes(payload);
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(uri)
                            .timeout(REQUEST_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            return send(builder.build(), MAX_JSON_BYTES);
        } catch (IOException exception) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_REQUEST_INVALID", "无法构造提供方请求", exception);
        }
    }

    final JsonNode json(Response response) {
        try {
            return json.readTree(response.body());
        } catch (IOException exception) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_RESPONSE_INVALID", "提供方返回了无法解析的 JSON", exception);
        }
    }

    final URI endpoint(URI base, String pathAndQuery) {
        String root = base.toString();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return URI.create(root + (pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Response send(HttpRequest request, int maximumBytes) {
        try {
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new PullRequestIntegrationException(
                        "PROVIDER_HTTP_ERROR",
                        "提供方请求失败 (HTTP " + response.statusCode() + ")");
            }
            byte[] body;
            try (InputStream input = response.body()) {
                body = input.readNBytes(maximumBytes + 1);
            }
            if (body.length > maximumBytes) {
                throw new PullRequestIntegrationException(
                        maximumBytes == MAX_PATCH_BYTES
                                ? "PROVIDER_PATCH_TOO_LARGE"
                                : "PROVIDER_RESPONSE_TOO_LARGE",
                        maximumBytes == MAX_PATCH_BYTES
                                ? "PR/MR Patch 超过 5 MiB，未生成不完整审查"
                                : "提供方响应超过安全读取上限");
            }
            return new Response(
                    response.statusCode(), body, response.headers().firstValue("x-next-page").orElse(null));
        } catch (HttpTimeoutException exception) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_TIMEOUT", "提供方请求超时", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PullRequestIntegrationException(
                    "PROVIDER_INTERRUPTED", "提供方请求被中断", exception);
        } catch (IOException exception) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_UNAVAILABLE", "无法连接 PR/MR 提供方", exception);
        }
    }

    record Response(int status, byte[] body, String nextPage) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
