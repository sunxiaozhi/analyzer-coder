package com.analyzercoder.application.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ProviderWebhookAuthenticatorTest {
    @Test
    void validatesGithubHmacAndGitlabTokenWithoutAcceptingWrongSecrets() throws Exception {
        byte[] payload = "{\"event\":true}".getBytes(StandardCharsets.UTF_8);
        ProviderWebhookAuthenticator authenticator =
                new ProviderWebhookAuthenticator("github-secret", "gitlab-secret");

        authenticator.verifyGithub(payload, "sha256=" + hmac(payload, "github-secret"));
        authenticator.verifyGitlab(payload, "gitlab-secret");

        assertThatThrownBy(() -> authenticator.verifyGithub(payload, "sha256=wrong"))
                .isInstanceOfSatisfying(
                        PullRequestIntegrationException.class,
                        error -> assertThat(error.code()).isEqualTo("WEBHOOK_SIGNATURE_INVALID"));
        assertThatThrownBy(() -> authenticator.verifyGitlab(payload, "wrong"))
                .isInstanceOfSatisfying(
                        PullRequestIntegrationException.class,
                        error -> assertThat(error.code()).isEqualTo("WEBHOOK_SIGNATURE_INVALID"));
    }

    @Test
    void rejectsDisabledOrOversizedWebhookBeforeParsing() {
        ProviderWebhookAuthenticator disabled = new ProviderWebhookAuthenticator("", "");
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> disabled.verifyGithub(payload, "sha256=anything"))
                .isInstanceOfSatisfying(
                        PullRequestIntegrationException.class,
                        error -> assertThat(error.code()).isEqualTo("WEBHOOK_NOT_CONFIGURED"));
        assertThatThrownBy(
                        () ->
                                new ProviderWebhookAuthenticator("secret", "secret")
                                        .verifyGitlab(
                                                new byte[ProviderWebhookAuthenticator.MAX_PAYLOAD_BYTES + 1],
                                                "secret"))
                .isInstanceOfSatisfying(
                        PullRequestIntegrationException.class,
                        error -> assertThat(error.code()).isEqualTo("WEBHOOK_PAYLOAD_INVALID"));
    }

    private static String hmac(byte[] payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
