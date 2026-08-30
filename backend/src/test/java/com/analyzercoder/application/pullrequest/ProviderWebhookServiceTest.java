package com.analyzercoder.application.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.infrastructure.persistence.model.RepositoryRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProviderWebhookServiceTest {
    private final RepositoryMapper repositories = mock(RepositoryMapper.class);
    private final PullRequestReviewService reviews = mock(PullRequestReviewService.class);
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final PullRequestReviewService.ReviewResult reviewResult =
            mock(PullRequestReviewService.ReviewResult.class);
    private ProviderWebhookService service;

    @BeforeEach
    void setUp() {
        service =
                new ProviderWebhookService(
                        repositories, reviews, new ObjectMapper().findAndRegisterModules());
        when(repositories.findIdsByRemoteUrls(
                        List.of(
                                "https://github.com/acme/app.git",
                                "https://github.com/acme/app")))
                .thenReturn(List.of(repositoryId));
        when(repositories.findById(repositoryId)).thenReturn(repositoryRow());
        when(reviews.reviewWebhook(eq(CodeRepositoryId.of(repositoryId)), eq(ownerId), any()))
                .thenReturn(reviewResult);
    }

    @Test
    void mapsSignedGithubPayloadToOneRepositoryAndUsesHeadBasedIdempotency() {
        byte[] payload = githubPayload("synchronize");

        ProviderWebhookService.WebhookResult first =
                service.github("pull_request", "delivery-one", payload);
        ProviderWebhookService.WebhookResult repeated =
                service.github("pull_request", "delivery-two", payload);

        assertThat(first.accepted()).isTrue();
        assertThat(first.repositoryId()).isEqualTo(repositoryId);
        assertThat(first.clientRequestId()).isEqualTo(repeated.clientRequestId());
        ArgumentCaptor<PullRequestReviewService.ReviewRequest> requests =
                ArgumentCaptor.forClass(PullRequestReviewService.ReviewRequest.class);
        verify(reviews, org.mockito.Mockito.times(2))
                .reviewWebhook(eq(CodeRepositoryId.of(repositoryId)), eq(ownerId), requests.capture());
        assertThat(requests.getAllValues())
                .extracting(PullRequestReviewService.ReviewRequest::clientRequestId)
                .containsOnly(first.clientRequestId());
    }

    @Test
    void mapsGitlabMergeRequestPayloadAndNestedLastCommit() {
        when(repositories.findIdsByRemoteUrls(
                        List.of(
                                "https://gitlab.example.com/group/app.git",
                                "https://gitlab.example.com/group/app")))
                .thenReturn(List.of(repositoryId));
        String payload =
                "{\"project\":{\"git_http_url\":\"https://gitlab.example.com/group/app.git\"},"
                        + "\"object_attributes\":{\"action\":\"update\",\"iid\":11,\"last_commit\":{\"id\":\""
                        + "c".repeat(40)
                        + "\"}}}";

        ProviderWebhookService.WebhookResult result =
                service.gitlab(
                        "Merge Request Hook",
                        "event-id",
                        payload.getBytes(StandardCharsets.UTF_8));

        assertThat(result.accepted()).isTrue();
        assertThat(result.provider()).isEqualTo(PullRequestProvider.ProviderKind.GITLAB);
        assertThat(result.number()).isEqualTo(11);
        ArgumentCaptor<PullRequestReviewService.ReviewRequest> request =
                ArgumentCaptor.forClass(PullRequestReviewService.ReviewRequest.class);
        verify(reviews)
                .reviewWebhook(eq(CodeRepositoryId.of(repositoryId)), eq(ownerId), request.capture());
        assertThat(request.getValue().provider())
                .isEqualTo(PullRequestProvider.ProviderKind.GITLAB);
    }

    @Test
    void ignoresUnrelatedActionsWithoutLookingUpARepository() {
        ProviderWebhookService.WebhookResult result =
                service.github("pull_request", "delivery", githubPayload("closed"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).isEqualTo("ACTION_IGNORED");
        verify(reviews, never()).reviewWebhook(any(), any(), any());
    }

    @Test
    void rejectsAmbiguousRemoteMappingsInsteadOfChoosingOne() {
        when(repositories.findIdsByRemoteUrls(any()))
                .thenReturn(List.of(repositoryId, UUID.randomUUID()));

        assertThatThrownBy(
                        () ->
                                service.github(
                                        "pull_request",
                                        "delivery",
                                        githubPayload("opened")))
                .isInstanceOfSatisfying(
                        PullRequestIntegrationException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo("WEBHOOK_REPOSITORY_AMBIGUOUS"));
        verify(reviews, never()).reviewWebhook(any(), any(), any());
    }

    private byte[] githubPayload(String action) {
        String json =
                "{\"action\":\""
                        + action
                        + "\",\"number\":7,\"repository\":{\"clone_url\":\"https://github.com/acme/app.git\"},"
                        + "\"pull_request\":{\"head\":{\"sha\":\""
                        + "b".repeat(40)
                        + "\"}}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private RepositoryRow repositoryRow() {
        Instant now = Instant.now();
        return new RepositoryRow(
                repositoryId,
                "app",
                "app",
                "C:/managed/app",
                "REMOTE_GIT",
                "main",
                "b".repeat(40),
                null,
                false,
                UUID.randomUUID(),
                "C:/managed/snapshot",
                "C:/managed/codegraph",
                now,
                now,
                ownerId,
                1,
                "READY",
                now,
                now,
                "",
                1);
    }
}
