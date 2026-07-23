package com.analyzercoder.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeAttachmentMapper {
    int insert(@Param("id") UUID id, @Param("repositoryId") UUID repositoryId,
               @Param("originalName") String originalName, @Param("mediaType") String mediaType,
               @Param("sizeBytes") long sizeBytes, @Param("sha256") String sha256,
               @Param("storagePath") String storagePath, @Param("actorId") UUID actorId,
               @Param("createdAt") Instant createdAt);
    Map<String, Object> find(@Param("repositoryId") UUID repositoryId, @Param("id") UUID id);
    List<Map<String, Object>> listForCard(@Param("repositoryId") UUID repositoryId, @Param("cardId") UUID cardId,
                                         @Param("revision") int revision);
    int countForRevision(@Param("cardId") UUID cardId, @Param("revision") int revision);
    long totalBytesForRevision(@Param("cardId") UUID cardId, @Param("revision") int revision);
    int insertRef(@Param("cardId") UUID cardId, @Param("revision") int revision,
                  @Param("attachmentId") UUID attachmentId, @Param("position") int position);
}
