package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.common.PageResult;
import com.analyzercoder.application.indexing.VectorIndexQueryService;
import com.analyzercoder.application.indexing.VectorIndexQueryService.ChunkItem;
import com.analyzercoder.application.indexing.VectorIndexQueryService.KnowledgeItem;
import com.analyzercoder.application.indexing.VectorIndexQueryService.Summary;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/vector-index")
public class VectorIndexController {
    private final VectorIndexQueryService service;
    private final AccessControlService accessControl;

    public VectorIndexController(VectorIndexQueryService service, AccessControlService accessControl) {
        this.service = service;
        this.accessControl = accessControl;
    }

    @GetMapping("/summary")
    public Summary summary(@PathVariable UUID repositoryId, HttpServletRequest request) {
        requireRead(repositoryId, request);
        return service.summary(repositoryId);
    }

    @GetMapping("/chunks")
    public PageResult<ChunkItem> chunks(
        @PathVariable UUID repositoryId,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String chunkType,
        @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "15") int pageSize,
        HttpServletRequest request
    ) {
        requireRead(repositoryId, request);
        return service.chunks(repositoryId, q, status, chunkType, pageNum, pageSize);
    }

    @GetMapping("/knowledge")
    public PageResult<KnowledgeItem> knowledge(
        @PathVariable UUID repositoryId,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "15") int pageSize,
        HttpServletRequest request
    ) {
        requireRead(repositoryId, request);
        return service.knowledge(repositoryId, q, status, pageNum, pageSize);
    }

    private void requireRead(UUID repositoryId, HttpServletRequest request) {
        accessControl.require(
            SecurityContext.account(request),
            CodeRepositoryId.of(repositoryId),
            RepositoryPermission.READ
        );
    }
}
