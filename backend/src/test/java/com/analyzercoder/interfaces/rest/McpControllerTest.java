package com.analyzercoder.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.analyzercoder.application.mcp.McpToolCatalog;
import com.analyzercoder.application.mcp.McpToolService;
import com.analyzercoder.security.AccessTokenInterceptor;
import com.analyzercoder.security.AccessTokenService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.AuthenticatedSession;
import com.analyzercoder.security.SessionInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class McpControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpToolService tools = mock(McpToolService.class);
    private final AccessTokenService tokens = mock(AccessTokenService.class);
    private final AuthService auth = mock(AuthService.class);
    private final AuthenticatedAccount alice =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "alice", "Alice", AccountRole.NORMAL, false, null);
    private final AuthenticatedAccount bob =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "bob", "Bob", AccountRole.NORMAL, false, null);
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new McpController(new McpToolCatalog(json), tools, json),
                                new AccountAccessTokenController(tokens))
                        .addInterceptors(
                                new AccessTokenInterceptor(tokens), new SessionInterceptor(auth))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
        when(tokens.authenticate("alice-token")).thenReturn(alice);
        when(tokens.authenticate("bob-token")).thenReturn(bob);
    }

    private String call() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search_project\",\"arguments\":{\"repositoryId\":\""
                + UUID.randomUUID()
                + "\",\"query\":\"订单超时\"}}}";
    }

    @Test
    void requiresBearerEvenWhenCookieExists() throws Exception {
        mvc.perform(
                        post("/api/mcp")
                                .cookie(new Cookie("AC_SESSION", "session"))
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tools);
    }

    @Test
    void initializeListsOneToolAndAcceptsNotification() throws Exception {
        mvc.perform(
                        post("/api/mcp")
                                .header("Authorization", "Bearer alice-token")
                                .contentType("application/json")
                                .content(
                                        "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"));
        mvc.perform(
                        post("/api/mcp")
                                .header("Authorization", "Bearer alice-token")
                                .contentType("application/json")
                                .content(
                                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                .andExpect(jsonPath("$.result.tools.length()").value(1));
        mvc.perform(
                        post("/api/mcp")
                                .header("Authorization", "Bearer alice-token")
                                .contentType("application/json")
                                .content(
                                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(get("/api/mcp").header("Authorization", "Bearer alice-token"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void passesPerRequestAccountAndRechecksToken() throws Exception {
        when(tools.call(anyString(), any(), any(), anyString()))
                .thenReturn(json.createObjectNode().put("ok", true));
        for (String token : new String[] {"alice-token", "bob-token"}) {
            mvc.perform(
                            post("/api/mcp")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType("application/json")
                                    .content(call()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.isError").value(false));
        }
        verify(tools).call(eq("search_project"), any(), eq(alice), anyString());
        verify(tools).call(eq("search_project"), any(), eq(bob), anyString());
        when(tokens.authenticate("alice-token"))
                .thenThrow(new ApiSecurityException(401, "ACCESS_TOKEN_INVALID", "revoked"));
        mvc.perform(
                        post("/api/mcp")
                                .header("Authorization", "Bearer alice-token")
                                .contentType("application/json")
                                .content(call()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsForeignOriginAndUnsupportedVersion() throws Exception {
        mvc.perform(
                        post("/api/mcp")
                                .header("Authorization", "Bearer alice-token")
                                .header("Origin", "https://evil.test")
                                .contentType("application/json")
                                .content(call()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/mcp")
                                .header("Authorization", "Bearer alice-token")
                                .header("MCP-Protocol-Version", "unknown")
                                .contentType("application/json")
                                .content(call()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(tools);
    }

    @Test
    void invalidArgumentsDoNotExecuteTools() throws Exception {
        mvc.perform(
                        post("/api/mcp")
                                .header("Authorization", "Bearer alice-token")
                                .contentType("application/json")
                                .content(
                                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search_project\",\"arguments\":{}}}"))
                .andExpect(jsonPath("$.result.isError").value(true));
        verifyNoInteractions(tools);
    }

    @Test
    void tokenCannotManageTokensAndCookieMutationsStillRequireCsrf() throws Exception {
        String url = "/api/accounts/" + alice.id() + "/access-tokens";
        mvc.perform(
                        post(url)
                                .header("Authorization", "Bearer alice-token")
                                .contentType("application/json")
                                .content("{\"name\":\"test\",\"expiresInDays\":30}"))
                .andExpect(status().isForbidden());
        when(auth.authenticate("session"))
                .thenReturn(Optional.of(new AuthenticatedSession("hash", "csrf", alice)));
        mvc.perform(
                        post(url)
                                .cookie(new Cookie("AC_SESSION", "session"))
                                .contentType("application/json")
                                .content("{\"name\":\"test\",\"expiresInDays\":30}"))
                .andExpect(status().isForbidden());
    }
}
