package com.analyzercoder.application.memory;

import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 旧版 Context Pack 兼容门面。
 *
 * <p>保留一个版本周期，业务逻辑全部委托 {@link TaskContextService}，避免形成第二套真实性规则。
 */
@Service
public class ProjectContextPackService {
    private final TaskContextService taskContexts;

    public ProjectContextPackService(TaskContextService taskContexts) {
        this.taskContexts = taskContexts;
    }

    public ContextPack generate(
            CodeRepositoryId repositoryId,
            String task,
            Integer requestedItems,
            Integer requestedChars) {
        TaskContextService.TaskContext context =
                taskContexts.generate(
                        repositoryId,
                        task,
                        null,
                        requestedItems,
                        requestedChars,
                        null);
        List<ContextItem> items = context.entries().stream().map(ProjectContextPackService::item).toList();
        return new ContextPack(
                context.repositoryId(),
                context.repositoryName(),
                context.snapshotId(),
                context.commitSha(),
                context.task(),
                items,
                context.markdown());
    }

    private static ContextItem item(TaskContextService.ContextEntry entry) {
        String excerpt = entry.content() == null ? "" : entry.content().replaceAll("\\s+", " ").trim();
        if (excerpt.length() > 240) excerpt = excerpt.substring(0, 240) + "…";
        RepositoryAssetType assetType =
                switch (entry.type()) {
                    case VERIFIED_KNOWLEDGE -> RepositoryAssetType.RULE;
                    case CODE_FACT -> RepositoryAssetType.CODE;
                    case RETRIEVAL_CANDIDATE -> RepositoryAssetType.DOCUMENT;
                    case UNKNOWN -> RepositoryAssetType.TASK;
                };
        return new ContextItem(
                entry.id(),
                entry.chunkId(),
                assetType,
                entry.type().name(),
                entry.title(),
                entry.filePath(),
                entry.symbolName(),
                entry.startLine(),
                entry.endLine(),
                excerpt,
                entry.content(),
                entry.contentHash());
    }

    public record ContextPack(
            UUID repositoryId,
            String repositoryName,
            UUID snapshotId,
            String commitSha,
            String task,
            List<ContextItem> items,
            String markdown) {}

    public record ContextItem(
            UUID id,
            UUID chunkId,
            RepositoryAssetType assetType,
            String sourceType,
            String title,
            String filePath,
            String symbolName,
            Integer startLine,
            Integer endLine,
            String excerpt,
            String content,
            String contentHash) {}
}
