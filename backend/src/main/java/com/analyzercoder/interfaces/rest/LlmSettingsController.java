package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.llm.LlmSettingsService;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/llm")
public class LlmSettingsController {
    private final LlmSettingsService service;

    public LlmSettingsController(LlmSettingsService service) {
        this.service = service;
    }

    @GetMapping("/provider")
    public LlmSettingsService.ProviderView provider(HttpServletRequest request) {
        SecurityContext.requireAdmin(request);
        return service.latest();
    }

    @GetMapping("/provider/versions")
    public List<LlmSettingsService.ProviderView> versions(HttpServletRequest request) {
        SecurityContext.requireAdmin(request);
        return service.versions();
    }

    @PutMapping("/provider")
    public LlmSettingsService.ProviderView save(
        @RequestBody LlmSettingsService.ProviderInput body,
        HttpServletRequest request
    ) {
        var account = SecurityContext.requireAdmin(request);
        return service.save(account.id(), body);
    }

    @PostMapping("/connectivity-checks")
    public LlmSettingsService.CheckView startCheck(
        @RequestBody LlmSettingsService.ConnectivityCheckRequest body,
        HttpServletRequest request
    ) {
        var account = SecurityContext.requireAdmin(request);
        return service.startCheck(account.id(), body);
    }

    @GetMapping("/connectivity-checks/{checkId}")
    public LlmSettingsService.CheckView check(
        @PathVariable UUID checkId,
        HttpServletRequest request
    ) {
        var account = SecurityContext.requireAdmin(request);
        return service.check(account.id(), checkId);
    }

    @PostMapping("/connectivity-checks/{checkId}/cancel")
    public LlmSettingsService.CheckView cancel(
        @PathVariable UUID checkId,
        HttpServletRequest request
    ) {
        var account = SecurityContext.requireAdmin(request);
        return service.cancelCheck(account.id(), checkId);
    }

    @PostMapping("/provider/{configId}/activate")
    public LlmSettingsService.ProviderView activate(
        @PathVariable UUID configId,
        @RequestBody LlmSettingsService.ActivationRequest body,
        HttpServletRequest request
    ) {
        var account = SecurityContext.requireAdmin(request);
        return service.activate(account.id(), configId, body);
    }

    @PostMapping("/provider/deactivate")
    public LlmSettingsService.ProviderView deactivate(
        @RequestParam long expectedActivationVersion,
        HttpServletRequest request
    ) {
        var account = SecurityContext.requireAdmin(request);
        return service.deactivate(account.id(), expectedActivationVersion);
    }
}
