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

/** 提供大模型设置相关 HTTP 接口，负责请求参数绑定并将已认证的调用委派给应用服务。 */
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

    @GetMapping("/providers")
    public List<LlmSettingsService.ProviderView> providers(HttpServletRequest request) {
        SecurityContext.requireAdmin(request);
        return service.providers();
    }

    @PostMapping("/providers")
    public LlmSettingsService.ProviderView createProvider(
            @RequestBody LlmSettingsService.ProviderInput body, HttpServletRequest request) {
        var account = SecurityContext.requireAdmin(request);
        return service.save(account.id(), body);
    }

    @PutMapping("/providers/{configId}")
    public LlmSettingsService.ProviderView updateProvider(
            @PathVariable UUID configId,
            @RequestBody LlmSettingsService.ProviderInput body,
            HttpServletRequest request) {
        var account = SecurityContext.requireAdmin(request);
        return service.update(account.id(), configId, body);
    }

    @GetMapping("/vector-models")
    public List<LlmSettingsService.VectorModelView> vectorModels(HttpServletRequest request) {
        SecurityContext.requireAdmin(request);
        return service.vectorModels();
    }

    @PostMapping("/vector-models")
    public LlmSettingsService.VectorModelView createVectorModel(
            @RequestBody LlmSettingsService.VectorModelInput body, HttpServletRequest request) {
        var account = SecurityContext.requireAdmin(request);
        return service.saveVectorModel(account.id(), body);
    }

    @PutMapping("/vector-models/{id}")
    public LlmSettingsService.VectorModelView updateVectorModel(
            @PathVariable UUID id,
            @RequestBody LlmSettingsService.VectorModelInput body,
            HttpServletRequest request) {
        var account = SecurityContext.requireAdmin(request);
        return service.updateVectorModel(account.id(), id, body);
    }

    @PostMapping("/vector-models/{id}/activate")
    public LlmSettingsService.VectorModelView activateVectorModel(
            @PathVariable UUID id,
            @RequestParam long expectedActivationVersion,
            HttpServletRequest request) {
        var account = SecurityContext.requireAdmin(request);
        return service.activateVectorModel(account.id(), id, expectedActivationVersion);
    }

    @PostMapping("/vector-models/{id}/check")
    public LlmSettingsService.VectorModelCheckView checkVectorModel(
            @PathVariable UUID id, HttpServletRequest request) {
        SecurityContext.requireAdmin(request);
        return service.checkVectorModel(id);
    }

    @PutMapping("/provider")
    public LlmSettingsService.ProviderView save(
            @RequestBody LlmSettingsService.ProviderInput body, HttpServletRequest request) {
        var account = SecurityContext.requireAdmin(request);
        return service.save(account.id(), body);
    }

    @PostMapping("/connectivity-checks")
    public LlmSettingsService.CheckView startCheck(
            @RequestBody LlmSettingsService.ConnectivityCheckRequest body,
            HttpServletRequest request) {
        var account = SecurityContext.requireAdmin(request);
        return service.startCheck(account.id(), body);
    }

    @GetMapping("/connectivity-checks/{checkId}")
    public LlmSettingsService.CheckView check(
            @PathVariable UUID checkId, HttpServletRequest request) {
        var account = SecurityContext.requireAdmin(request);
        return service.check(account.id(), checkId);
    }

    @PostMapping("/connectivity-checks/{checkId}/cancel")
    public LlmSettingsService.CheckView cancel(
            @PathVariable UUID checkId, HttpServletRequest request) {
        var account = SecurityContext.requireAdmin(request);
        return service.cancelCheck(account.id(), checkId);
    }

}
