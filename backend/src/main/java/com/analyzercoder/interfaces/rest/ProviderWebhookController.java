package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.pullrequest.ProviderWebhookAuthenticator;
import com.analyzercoder.application.pullrequest.ProviderWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GitHub/GitLab 无会话 Webhook；原始请求体验签通过前不解析、不访问仓库凭据。 */
@RestController
@RequestMapping("/api/provider-webhooks")
public class ProviderWebhookController {
    private final ProviderWebhookAuthenticator authenticator;
    private final ProviderWebhookService service;

    public ProviderWebhookController(
            ProviderWebhookAuthenticator authenticator, ProviderWebhookService service) {
        this.authenticator = authenticator;
        this.service = service;
    }

    @PostMapping(value = "/github", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProviderWebhookService.WebhookResult github(
            @RequestBody byte[] payload, HttpServletRequest request) {
        authenticator.verifyGithub(payload, request.getHeader("X-Hub-Signature-256"));
        return service.github(
                request.getHeader("X-GitHub-Event"),
                request.getHeader("X-GitHub-Delivery"),
                payload);
    }

    @PostMapping(value = "/gitlab", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProviderWebhookService.WebhookResult gitlab(
            @RequestBody byte[] payload, HttpServletRequest request) {
        authenticator.verifyGitlab(payload, request.getHeader("X-Gitlab-Token"));
        return service.gitlab(
                request.getHeader("X-Gitlab-Event"),
                request.getHeader("X-Gitlab-Event-UUID"),
                payload);
    }
}
