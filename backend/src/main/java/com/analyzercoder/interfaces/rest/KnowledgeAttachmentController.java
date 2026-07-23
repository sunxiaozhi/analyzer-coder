package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.intelligence.KnowledgeAttachmentService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/knowledge/attachments")
public class KnowledgeAttachmentController {
    private final KnowledgeAttachmentService service;
    private final AccessControlService access;

    public KnowledgeAttachmentController(KnowledgeAttachmentService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping
    public KnowledgeAttachmentService.Attachment upload(
        @PathVariable UUID repositoryId, @RequestPart("file") MultipartFile file, HttpServletRequest request
    ) {
        var account = SecurityContext.account(request);
        access.require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.MAINTAIN);
        return service.upload(repositoryId, account.id(), file);
    }

    @GetMapping("/{attachmentId}")
    public ResponseEntity<FileSystemResource> download(
        @PathVariable UUID repositoryId, @PathVariable UUID attachmentId, HttpServletRequest request
    ) {
        var account = SecurityContext.account(request);
        access.require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        var download = service.download(repositoryId, attachmentId);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(download.originalName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(download.mediaType()))
            .contentLength(download.sizeBytes())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(new FileSystemResource(download.path()));
    }
}
