package com.analyzercoder.application.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleTlsExceptionTest {
    private HttpsServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void skipsCertificateAndHostnameChecksOnlyForListedEndpoint() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input =
                Objects.requireNonNull(getClass().getResourceAsStream("/llm-untrusted-test.p12"))) {
            keyStore.load(input, "changeit".toCharArray());
        }
        KeyManagerFactory keys =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, "changeit".toCharArray());
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keys.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    byte[] response =
                            "{\"choices\":[{\"message\":{\"content\":\"CONNECTED\"}}]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();

        String baseUrl = "https://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        LlmProviderSpec spec =
                new LlmProviderSpec(
                        null,
                        1,
                        "test",
                        "OPENAI_COMPATIBLE",
                        baseUrl,
                        "test-model",
                        2000,
                        5000,
                        128,
                        0.2,
                        false,
                        null,
                        "fingerprint");

        OpenAiCompatibleClient strict = client(baseUrl, false);
        assertEquals(
                "LLM_TLS_FAILED",
                assertThrows(
                                LlmConnectionException.class,
                                () -> strict.generate(spec, "test-key", "hello"))
                        .code());

        OpenAiCompatibleClient excepted = client(baseUrl, true);
        assertEquals("CONNECTED", excepted.generate(spec, "test-key", "hello"));

        assertEquals(
                "LLM_TLS_FAILED",
                assertThrows(
                                LlmConnectionException.class,
                                () -> strict.generate(spec, "test-key", "hello"))
                        .code());
    }

    private static OpenAiCompatibleClient client(String baseUrl, boolean skipTlsVerification) {
        var exception =
                new LlmEndpointExceptionProperties.EndpointException(
                        baseUrl, true, skipTlsVerification);
        var properties = new LlmEndpointExceptionProperties(List.of(exception));
        return new OpenAiCompatibleClient(
                new ObjectMapper(), new LlmEndpointPolicy(false, properties));
    }
}
