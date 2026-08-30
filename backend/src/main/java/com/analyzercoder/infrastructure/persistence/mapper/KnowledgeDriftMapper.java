package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.KnowledgeDriftCandidateRow;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeDriftEventRow;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeDriftReferenceRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 保存精准知识漂移结果及人工来源复核审计。 */
@Mapper
public interface KnowledgeDriftMapper {
    List<KnowledgeDriftCandidateRow> candidates(@Param("repositoryId") UUID repositoryId);

    KnowledgeDriftCandidateRow findCandidate(
            @Param("repositoryId") UUID repositoryId, @Param("cardId") UUID cardId);

    List<KnowledgeDriftReferenceRow> references(
            @Param("repositoryId") UUID repositoryId,
            @Param("cardId") UUID cardId,
            @Param("revision") int revision);

    int markSuspect(
            @Param("repositoryId") UUID repositoryId,
            @Param("cardId") UUID cardId,
            @Param("expectedRevision") int expectedRevision,
            @Param("expectedCommit") String expectedCommit);

    int touchCurrent(
            @Param("repositoryId") UUID repositoryId,
            @Param("cardId") UUID cardId,
            @Param("expectedRevision") int expectedRevision,
            @Param("expectedCommit") String expectedCommit);

    int reviewSource(
            @Param("repositoryId") UUID repositoryId,
            @Param("cardId") UUID cardId,
            @Param("expectedRevision") int expectedRevision,
            @Param("resultStatus") String resultStatus,
            @Param("currentCommit") String currentCommit,
            @Param("currentSnapshotId") UUID currentSnapshotId,
            @Param("note") String note,
            @Param("actorId") UUID actorId);

    int insertEvent(KnowledgeDriftEventRow row);

    KnowledgeDriftEventRow latestEvent(
            @Param("repositoryId") UUID repositoryId, @Param("cardId") UUID cardId);
}
