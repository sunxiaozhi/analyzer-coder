package com.analyzercoder.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persists the current repository Markdown manifest and card-generation provenance. */
@Mapper
public interface MarkdownKnowledgeSourceMapper {

    int upsertSource(
            @Param("id") UUID id,
            @Param("repositoryId") UUID repositoryId,
            @Param("contentVersion") UUID contentVersion,
            @Param("filePath") String filePath,
            @Param("contentHash") String contentHash,
            @Param("title") String title,
            @Param("assetType") String assetType,
            @Param("content") String content,
            @Param("lineCount") int lineCount,
            @Param("byteSize") long byteSize);

    int deleteMissingSources(
            @Param("repositoryId") UUID repositoryId, @Param("filePaths") List<String> filePaths);

    List<Map<String, Object>> listSources(
            @Param("repositoryId") UUID repositoryId, @Param("contentVersion") UUID contentVersion);

    Map<String, Object> findSource(
            @Param("repositoryId") UUID repositoryId,
            @Param("contentVersion") UUID contentVersion,
            @Param("filePath") String filePath);

    Map<String, Object> lockSource(
            @Param("repositoryId") UUID repositoryId, @Param("filePath") String filePath);

    List<UUID> findChunkIds(
            @Param("repositoryId") UUID repositoryId,
            @Param("contentVersion") UUID contentVersion,
            @Param("filePath") String filePath,
            @Param("limit") int limit);

    int insertProvenance(
            @Param("cardId") UUID cardId,
            @Param("revision") int revision,
            @Param("sourceId") UUID sourceId,
            @Param("repositoryId") UUID repositoryId,
            @Param("sourceContentVersion") UUID sourceContentVersion,
            @Param("sourcePath") String sourcePath,
            @Param("sourceContentHash") String sourceContentHash);

    int reconcileLinkedCards(
            @Param("repositoryId") UUID repositoryId,
            @Param("contentVersion") UUID contentVersion,
            @Param("commitSha") String commitSha,
            @Param("commitAvailable") boolean commitAvailable);

    int upsertBranchSource(
            @Param("id") UUID id,
            @Param("repositoryId") UUID repositoryId,
            @Param("branchId") UUID branchId,
            @Param("contentVersion") UUID contentVersion,
            @Param("filePath") String filePath,
            @Param("contentHash") String contentHash,
            @Param("title") String title,
            @Param("assetType") String assetType,
            @Param("content") String content,
            @Param("lineCount") int lineCount,
            @Param("byteSize") long byteSize);

    int deleteMissingBranchSources(
            @Param("repositoryId") UUID repositoryId,
            @Param("branchId") UUID branchId,
            @Param("filePaths") List<String> filePaths);

    List<Map<String, Object>> listBranchSources(
            @Param("repositoryId") UUID repositoryId,
            @Param("branchId") UUID branchId,
            @Param("contentVersion") UUID contentVersion);

    Map<String, Object> findBranchSource(
            @Param("repositoryId") UUID repositoryId,
            @Param("branchId") UUID branchId,
            @Param("contentVersion") UUID contentVersion,
            @Param("filePath") String filePath);

    Map<String, Object> lockBranchSource(
            @Param("repositoryId") UUID repositoryId,
            @Param("branchId") UUID branchId,
            @Param("filePath") String filePath);

    int insertBranchProvenance(
            @Param("cardId") UUID cardId,
            @Param("revision") int revision,
            @Param("sourceId") UUID sourceId,
            @Param("repositoryId") UUID repositoryId,
            @Param("sourceBranchId") UUID sourceBranchId,
            @Param("sourceContentVersion") UUID sourceContentVersion,
            @Param("sourcePath") String sourcePath,
            @Param("sourceContentHash") String sourceContentHash);

    int reconcileBranchValidations(
            @Param("repositoryId") UUID repositoryId,
            @Param("branchId") UUID branchId,
            @Param("contentVersion") UUID contentVersion);
}
