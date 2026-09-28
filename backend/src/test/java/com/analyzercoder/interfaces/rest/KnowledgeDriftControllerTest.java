package com.analyzercoder.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.branch.BranchKnowledgeService;
import com.analyzercoder.application.branch.BranchReadContext;
import com.analyzercoder.application.branch.RepositoryBranchService;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.knowledge.KnowledgeDriftService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.AuthenticatedSession;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeDriftControllerTest {
    private final BranchRequestContext contexts = mock(BranchRequestContext.class);
    private final RepositoryBranchService branches = mock(RepositoryBranchService.class);
    private final BranchKnowledgeService knowledge = mock(BranchKnowledgeService.class);
    private KnowledgeDriftService drift;
    private IntelligenceService intelligence;
    private AccessControlService access;
    private KnowledgeDriftController controller;
    private HttpServletRequest request;
    private AuthenticatedAccount account;

    @BeforeEach
    void setUp() {
        drift = mock(KnowledgeDriftService.class);
        intelligence = mock(IntelligenceService.class);
        access = mock(AccessControlService.class);
        controller =
                new KnowledgeDriftController(
                        drift, intelligence, access, contexts, branches, knowledge);
        request = mock(HttpServletRequest.class);
        account =
                new AuthenticatedAccount(
                        UUID.randomUUID(),
                        "maintainer",
                        "维护者",
                        AccountRole.NORMAL,
                        false,
                        Instant.now());
        when(request.getAttribute(SecurityContext.SESSION_ATTRIBUTE))
                .thenReturn(new AuthenticatedSession("token", "csrf", account));
    }

    @Test
    void readingEvidenceRequiresReadAndReviewingRequiresMaintain() {
        UUID repositoryId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        var context =
                new BranchReadContext(
                        UUID.randomUUID(),
                        repositoryId,
                        UUID.randomUUID(),
                        "legacy",
                        UUID.randomUUID(),
                        "legacy-commit",
                        Path.of("."),
                        Instant.now());
        var repository = mock(CodeRepository.class);
        when(contexts.resolve(request, repositoryId)).thenReturn(context);
        when(knowledge.applicable(repositoryId, context.branchId())).thenReturn(Set.of(cardId));
        when(branches.repositoryFor(context)).thenReturn(repository);
        var body =
                new KnowledgeDriftController.SourceReviewRequest("CONFIRM_CURRENT", 4, "已核对当前实现");

        controller.latest(repositoryId, cardId, request);
        controller.review(repositoryId, cardId, body, request);

        verify(access)
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        verify(access)
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.MAINTAIN);
        verify(drift)
                .reviewSource(
                        eq(repository),
                        eq(cardId),
                        eq(account.id()),
                        any(KnowledgeDriftService.SourceReviewRequest.class));
    }
}
