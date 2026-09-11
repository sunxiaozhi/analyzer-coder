package com.analyzercoder.application.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpToolServiceTest {
    @Test
    void searchChecksLiveRepositoryPermissionBeforeReading() {
        var access = mock(AccessControlService.class);
        var intelligence = mock(IntelligenceService.class);
        var json = new ObjectMapper();
        var service = new McpToolService(access, intelligence, json);
        var account =
                new AuthenticatedAccount(
                        UUID.randomUUID(), "user", "User", AccountRole.NORMAL, false, null);
        var repositoryId = UUID.randomUUID();
        var input =
                json.createObjectNode()
                        .put("repositoryId", repositoryId.toString())
                        .put("query", "订单超时")
                        .put("limit", 20);
        doThrow(new ApiSecurityException(403, "FORBIDDEN", "denied"))
                .when(access)
                .require(eq(account), any(), eq(RepositoryPermission.READ));

        assertThatThrownBy(() -> service.call("search_project", input, account, "ip"))
                .isInstanceOf(ApiSecurityException.class);

        verifyNoInteractions(intelligence);
        verify(access)
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
    }

    @Test
    void catalogRejectsMissingQueryAndOversizedLimit() throws Exception {
        var json = new ObjectMapper();
        var catalog = new McpToolCatalog(json);
        var input = json.createObjectNode().put("repositoryId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> catalog.validate("search_project", input))
                .isInstanceOf(IllegalArgumentException.class);
        input.put("query", "订单").put("limit", 51);
        assertThatThrownBy(() -> catalog.validate("search_project", input))
                .isInstanceOf(IllegalArgumentException.class);
        input.put("limit", 20);
        catalog.validate("search_project", input);
    }
}
