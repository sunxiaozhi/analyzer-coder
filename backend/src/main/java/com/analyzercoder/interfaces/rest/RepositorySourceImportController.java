package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.repository.RepositorySourceImportService;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.repository.RemoteRepositoryTargetPolicy;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/repository-imports")
public class RepositorySourceImportController {
    private final RepositorySourceImportService service;
    private final AccessControlService accessControl;

    public RepositorySourceImportController(RepositorySourceImportService service, AccessControlService accessControl) {
        this.service = service;
        this.accessControl = accessControl;
    }

    @PostMapping("/remote")
    public RepositoryController.RepositoryResponse remote(@Valid @RequestBody RemoteInput input, HttpServletRequest request) {
        var account = SecurityContext.account(request);
        RemoteRepositoryTargetPolicy.requireAllowed(input.url());
        var repository = service.importRemote(input.name(), input.url(), input.branch(), input.sourceType(), account.id());
        return RepositoryController.RepositoryResponse.from(repository, accessControl.describe(account, repository.id()));
    }

    @PostMapping(value = "/zip", consumes = "multipart/form-data")
    public RepositoryController.RepositoryResponse zip(@RequestParam String name, @RequestPart MultipartFile file, HttpServletRequest request) {
        var account = SecurityContext.account(request);
        var repository = service.importZip(name, file, account.id());
        return RepositoryController.RepositoryResponse.from(repository, accessControl.describe(account, repository.id()));
    }

    public record RemoteInput(@NotBlank String name, @NotBlank String url, String branch, RepositorySourceType sourceType) {}
}