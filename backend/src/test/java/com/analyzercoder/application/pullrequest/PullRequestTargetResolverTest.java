package com.analyzercoder.application.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PullRequestTargetResolverTest {
    private final PullRequestTargetResolver resolver = new PullRequestTargetResolver();

    @Test
    void derivesPublicGithubAndSelfHostedGitlabApisFromRegisteredRemote() {
        PullRequestProvider.Reference github =
                resolver.resolve(
                        PullRequestProvider.ProviderKind.GITHUB,
                        "https://github.com/acme/app.git",
                        null,
                        8);
        PullRequestProvider.Reference gitlab =
                resolver.resolve(
                        PullRequestProvider.ProviderKind.GITLAB,
                        "https://git.example.com/group/sub/app.git",
                        null,
                        9);

        assertThat(github.apiBaseUrl().toString()).isEqualTo("https://api.github.com");
        assertThat(github.projectPath()).isEqualTo("acme/app");
        assertThat(gitlab.apiBaseUrl().toString()).isEqualTo("https://git.example.com/api/v4");
        assertThat(gitlab.projectPath()).isEqualTo("group/sub/app");
    }

    @Test
    void rejectsApiHostThatCouldLeakTheBoundToken() {
        assertThatThrownBy(
                        () ->
                                resolver.resolve(
                                        PullRequestProvider.ProviderKind.GITLAB,
                                        "https://git.example.com/group/app.git",
                                        "https://attacker.example/api/v4",
                                        1))
                .isInstanceOfSatisfying(
                        PullRequestIntegrationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PROVIDER_HOST_MISMATCH"));
    }
}
