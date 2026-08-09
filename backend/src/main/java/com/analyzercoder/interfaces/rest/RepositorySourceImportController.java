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
    private final com.analyzercoder.application.repository.RepositoryImportJobService jobs;

    public RepositorySourceImportController(RepositorySourceImportService service, AccessControlService accessControl,
        com.analyzercoder.application.repository.RepositoryImportJobService jobs) {
        this.service = service;
        this.accessControl = accessControl;
        this.jobs=jobs;
    }

    @PostMapping("/remote-jobs")
    public com.analyzercoder.application.repository.RepositoryImportJobService.JobView remoteJob(
        @Valid @RequestBody RemoteInput input,HttpServletRequest request){
        return jobs.submit(SecurityContext.account(request),input.name(),input.url(),input.branch(),input.sourceType(),input.credentialId());
    }
    @GetMapping("/jobs") public java.util.List<com.analyzercoder.application.repository.RepositoryImportJobService.JobView> jobs(HttpServletRequest request){return jobs.list(SecurityContext.account(request));}
    @GetMapping("/jobs/{id}") public com.analyzercoder.application.repository.RepositoryImportJobService.JobView job(@PathVariable java.util.UUID id,HttpServletRequest request){return jobs.get(SecurityContext.account(request),id);}
    @PostMapping("/jobs/{id}/cancel") public com.analyzercoder.application.repository.RepositoryImportJobService.JobView cancel(@PathVariable java.util.UUID id,HttpServletRequest request){return jobs.cancel(SecurityContext.account(request),id);}

    @PostMapping("/remote")
    public RepositoryController.RepositoryResponse remote(@Valid @RequestBody RemoteInput input, HttpServletRequest request) {
        var account = SecurityContext.account(request);
        RemoteRepositoryTargetPolicy.requireAllowed(input.url());
        var repository = service.importRemote(input.name(), input.url(), input.branch(), input.sourceType(),
            input.credentialId(), account);
        return RepositoryController.RepositoryResponse.from(repository, accessControl.describe(account, repository.id()));
    }

    @PostMapping(value = "/zip", consumes = "multipart/form-data")
    public RepositoryController.RepositoryResponse zip(@RequestParam String name, @RequestPart MultipartFile file, HttpServletRequest request) {
        var account = SecurityContext.account(request);
        var repository = service.importZip(name, file, account.id());
        return RepositoryController.RepositoryResponse.from(repository, accessControl.describe(account, repository.id()));
    }

    public record RemoteInput(@NotBlank String name, @NotBlank String url, String branch,
        RepositorySourceType sourceType, java.util.UUID credentialId) {}
}
