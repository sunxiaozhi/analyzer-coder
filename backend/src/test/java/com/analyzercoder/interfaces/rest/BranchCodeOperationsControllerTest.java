package com.analyzercoder.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.branch.BranchCodeOperationsService;
import com.analyzercoder.application.branch.BranchPreparationJobs;
import com.analyzercoder.application.branch.BranchReadContext;
import com.analyzercoder.application.branch.RepositoryBranchService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.SecurityContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BranchCodeOperationsControllerTest {
    final UUID repo = UUID.randomUUID(),
            branch = UUID.randomUUID(),
            ctx = UUID.randomUUID(),
            contentVersion = UUID.randomUUID();
    final BranchPreparationJobs jobs = mock(BranchPreparationJobs.class);
    final RepositoryBranchService branches = mock(RepositoryBranchService.class);
    final BranchCodeOperationsService operations = mock(BranchCodeOperationsService.class);
    final BranchCodeOperationsController controller =
            new BranchCodeOperationsController(jobs, branches, operations);
    final AuthenticatedAccount actor =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "member", "Member", AccountRole.NORMAL, false, null);

    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setAttribute(SecurityContext.TOKEN_ACCOUNT_ATTRIBUTE, actor);
        return request;
    }

    @Test
    void indexRequiresPinnedContextAndResolvesAgainstTheRequestedBranch() {
        assertThatThrownBy(
                        () ->
                                controller.start(
                                        repo,
                                        branch,
                                        new BranchCodeOperationsController.Operation(
                                                "CONTENT", null),
                                        request()))
                .hasMessageContaining("分支内容版本");
        verifyNoInteractions(jobs);
        when(branches.resolve(actor, repo, branch, ctx))
                .thenReturn(
                        new BranchReadContext(
                                ctx,
                                repo,
                                branch,
                                "main",
                                contentVersion,
                                "a".repeat(40),
                                Path.of("."),
                                Instant.now().plusSeconds(60)));
        controller.start(
                repo,
                branch,
                new BranchCodeOperationsController.Operation("CONTENT", ctx),
                request());
        verify(branches).resolve(actor, repo, branch, ctx);
        verify(jobs).submitOperation(actor, repo, branch, "CONTENT", contentVersion);
    }

    @Test
    void syncAndPrepareNeverTakeHistoricalReadCoordinates() {
        assertThatThrownBy(
                        () ->
                                controller.start(
                                        repo,
                                        branch,
                                        new BranchCodeOperationsController.Operation("SYNC", ctx),
                                        request()))
                .hasMessageContaining("历史");
        controller.start(
                repo,
                branch,
                new BranchCodeOperationsController.Operation("SYNC", null),
                request());
        controller.start(
                repo,
                branch,
                new BranchCodeOperationsController.Operation("PREPARE", null),
                request());
        verify(jobs).submitOperation(actor, repo, branch, "SYNC", null);
        verify(jobs).submitOperation(actor, repo, branch, "PREPARE", null);
        verifyNoInteractions(branches);
    }

    @Test
    void invalidBranchContextCannotEnqueueAnIndex() {
        when(branches.resolve(any(), any(), any(), any()))
                .thenThrow(new ApiSecurityException(403, "CONTEXT", "wrong branch"));
        assertThatThrownBy(
                        () ->
                                controller.start(
                                        repo,
                                        branch,
                                        new BranchCodeOperationsController.Operation("GRAPH", ctx),
                                        request()))
                .isInstanceOf(ApiSecurityException.class);
        verifyNoInteractions(jobs);
    }

    @Test
    void statusesUseProjectReadAuthorizationInTheService() {
        controller.statuses(repo, request());
        verify(operations).statuses(actor, repo);
    }
}
