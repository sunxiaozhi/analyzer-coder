package com.analyzercoder.application.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.analyzercoder.application.memory.TaskContextService;
import com.analyzercoder.application.outcome.TaskReviewOutcomeService;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpToolServiceTest {
    @Test
    void everyToolChecksLiveRepositoryPermissionBeforeReadingOrWriting() {
        var access = mock(AccessControlService.class);
        var reviews = mock(TaskReviewService.class);
        var contexts = mock(TaskContextService.class);
        var outcomes = mock(TaskReviewOutcomeService.class);
        var json = new ObjectMapper();
        var service = new McpToolService(access, reviews, contexts, outcomes, json);
        var account =
                new AuthenticatedAccount(
                        UUID.randomUUID(), "user", "User", AccountRole.NORMAL, false, null);
        var repositoryId = UUID.randomUUID();
        var input =
                json.createObjectNode()
                        .put("repositoryId", repositoryId.toString())
                        .put("reviewId", UUID.randomUUID().toString());
        doThrow(new ApiSecurityException(403, "FORBIDDEN", "denied"))
                .when(access)
                .require(eq(account), any(), eq(RepositoryPermission.READ));
        for (String tool :
                List.of(
                        "get_task_context",
                        "review_change",
                        "get_rules_for_symbol",
                        "get_required_tests",
                        "get_stale_knowledge",
                        "get_evidence",
                        "report_task_outcome")) {
            assertThatThrownBy(() -> service.call(tool, input, account, "ip"))
                    .isInstanceOf(ApiSecurityException.class);
        }
        verifyNoInteractions(reviews, contexts, outcomes);
        verify(access, org.mockito.Mockito.times(7))
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
    }

    @Test
    void catalogRejectsOversizedPayloadAndBadEnums() throws Exception {
        var json = new ObjectMapper();
        var catalog = new McpToolCatalog(json);
        var input =
                json.createObjectNode()
                        .put("repositoryId", UUID.randomUUID().toString())
                        .put("task", "x")
                        .put("maxItems", 100);
        assertThatThrownBy(() -> catalog.validate("get_task_context", input))
                .isInstanceOf(IllegalArgumentException.class);
        input.remove("maxItems");
        catalog.validate("get_task_context", input);
        input.put("changeSource", "UNSUPPORTED");
        assertThatThrownBy(() -> catalog.validate("review_change", input))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
