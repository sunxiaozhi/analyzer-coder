package com.analyzercoder.application.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.repository.GitCredentialExecutor;
import com.analyzercoder.application.repository.RepositoryCredentialService;
import com.analyzercoder.application.review.TaskReviewRequest;
import com.analyzercoder.application.review.TaskReviewResult;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.infrastructure.repository.InMemoryCodeRepositoryStore;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthenticatedAccount;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PullRequestReviewServiceTest {
    @TempDir Path workspace;

    private final InMemoryCodeRepositoryStore repositories = new InMemoryCodeRepositoryStore();
    private final RepositoryMapper repositoryMapper = mock(RepositoryMapper.class);
    private final RepositoryCredentialService credentials = mock(RepositoryCredentialService.class);
    private final TaskReviewService taskReviews = mock(TaskReviewService.class);
    private final PullRequestReviewCommentRenderer renderer = mock(PullRequestReviewCommentRenderer.class);
    private final FakeProvider provider = new FakeProvider();
    private final AuthenticatedAccount actor =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "maintainer", "维护者", AccountRole.NORMAL, false, Instant.now());
    private CodeRepository repository;
    private PullRequestReviewService service;
    private TaskReviewResult savedReview;

    @BeforeEach
    void setUp() {
        String commit = "b".repeat(40);
        Instant now = Instant.now();
        repository =
                new CodeRepository(
                        CodeRepositoryId.newId(),
                        "remote",
                        workspace,
                        RepositorySourceType.REMOTE_GIT,
                        "main",
                        commit,
                        null,
                        false,
                        RepositorySnapshotId.of(UUID.randomUUID()),
                        workspace,
                        workspace.resolve(".codegraph"),
                        now,
                        now,
                        now,
                        now);
        repositories.save(repository);
        when(repositoryMapper.findRemoteUrl(repository.id().value()))
                .thenReturn("https://github.com/acme/app.git");
        when(credentials.resolveBound(actor, repository.id().value(), "https://github.com/acme/app.git"))
                .thenReturn(
                        new RepositoryCredentialService.Resolved(
                                UUID.randomUUID(),
                                new GitCredentialExecutor.ResolvedCredential("git", "secret-token")));
        savedReview = mock(TaskReviewResult.class);
        when(taskReviews.createExternal(eq(repository.id()), eq(actor.id()), any(), any()))
                .thenReturn(savedReview);
        when(renderer.render(any(), any(), eq(savedReview))).thenReturn("rendered comment");
        service =
                new PullRequestReviewService(
                        repositories,
                        repositoryMapper,
                        credentials,
                        new PullRequestTargetResolver(),
                        new UnifiedDiffRepositoryChangeParser(),
                        taskReviews,
                        renderer,
                        List.of(provider));
    }

    @Test
    void reviewsTheProviderPatchAndPublishesOneInformationalComment() {
        UUID requestId = UUID.randomUUID();
        PullRequestReviewService.ReviewResult result =
                service.review(
                        actor,
                        repository.id(),
                        new PullRequestReviewService.ReviewRequest(
                                requestId,
                                PullRequestProvider.ProviderKind.GITHUB,
                                7,
                                "核对退款改动",
                                null,
                                null));

        assertThat(result.review()).isSameAs(savedReview);
        assertThat(result.comment().action())
                .isEqualTo(PullRequestProvider.CommentAction.UPDATED);
        assertThat(provider.publishedBody).isEqualTo("rendered comment");
        assertThat(provider.publishedMarker)
                .isEqualTo(
                        "<!-- analyzer-coder:task-review:"
                                + repository.id().value()
                                + ":github:7 -->");
        org.mockito.ArgumentCaptor<TaskReviewRequest> request =
                org.mockito.ArgumentCaptor.forClass(TaskReviewRequest.class);
        org.mockito.ArgumentCaptor<RepositoryChange> change =
                org.mockito.ArgumentCaptor.forClass(RepositoryChange.class);
        verify(taskReviews)
                .createExternal(eq(repository.id()), eq(actor.id()), request.capture(), change.capture());
        assertThat(request.getValue().clientRequestId()).isEqualTo(requestId);
        assertThat(request.getValue().baseRef()).isEqualTo("a".repeat(40));
        assertThat(change.getValue().changes()).hasSize(1);
    }

    @Test
    void refusesToReviewOrCommentWhenProviderHeadIsNotThePublishedSnapshot() {
        provider.headSha = "c".repeat(40);

        assertThatThrownBy(
                        () ->
                                service.review(
                                        actor,
                                        repository.id(),
                                        new PullRequestReviewService.ReviewRequest(
                                                UUID.randomUUID(),
                                                PullRequestProvider.ProviderKind.GITHUB,
                                                7,
                                                null,
                                                null,
                                                null)))
                .isInstanceOfSatisfying(
                        PullRequestIntegrationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PR_HEAD_NOT_CURRENT_SNAPSHOT"));
        verify(taskReviews, never()).createExternal(any(), any(), any(), any());
        assertThat(provider.publishedBody).isNull();
    }

    private static final class FakeProvider implements PullRequestProvider {
        private String headSha = "b".repeat(40);
        private String publishedMarker;
        private String publishedBody;

        @Override
        public ProviderKind kind() {
            return ProviderKind.GITHUB;
        }

        @Override
        public PullRequestSnapshot fetch(Reference reference, AccessToken accessToken) {
            return new PullRequestSnapshot(
                    kind(),
                    reference.externalId(),
                    reference.number(),
                    "Refund update",
                    "https://github.com/acme/app/pull/7",
                    "dev",
                    false,
                    "a".repeat(40),
                    headSha,
                    "diff --git a/src/App.java b/src/App.java\n"
                            + "--- a/src/App.java\n+++ b/src/App.java\n"
                            + "@@ -1 +1 @@\n-old\n+new\n",
                    false,
                    List.of(),
                    Instant.now());
        }

        @Override
        public CommentResult upsertReviewComment(
                Reference reference, String marker, String body, AccessToken accessToken) {
            publishedMarker = marker;
            publishedBody = body;
            return new CommentResult(CommentAction.UPDATED, "15", "https://comment/15");
        }
    }
}
