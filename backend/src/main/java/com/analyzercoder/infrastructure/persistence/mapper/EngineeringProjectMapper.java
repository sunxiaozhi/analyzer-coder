package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.CurrentPathChunkRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringContractRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringProjectRepositoryRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringProjectRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringReviewContractRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringReviewRepositoryRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EngineeringProjectMapper {
    List<EngineeringProjectRow> listVisible(
            @Param("accountId") UUID accountId, @Param("superAdmin") boolean superAdmin);

    EngineeringProjectRow findVisible(
            @Param("id") UUID id,
            @Param("accountId") UUID accountId,
            @Param("superAdmin") boolean superAdmin);

    EngineeringProjectRow findById(@Param("id") UUID id);

    List<EngineeringProjectRepositoryRow> repositories(@Param("projectId") UUID projectId);

    List<EngineeringContractRow> contracts(@Param("projectId") UUID projectId);

    int insertProject(
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("normalizedName") String normalizedName,
            @Param("description") String description,
            @Param("actorId") UUID actorId,
            @Param("now") Instant now);

    int updateProject(
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("normalizedName") String normalizedName,
            @Param("description") String description,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);

    int softDelete(
            @Param("id") UUID id,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);

    int deleteContracts(@Param("projectId") UUID projectId);

    int deleteRepositories(@Param("projectId") UUID projectId);

    int insertRepository(
            @Param("projectId") UUID projectId,
            @Param("repositoryId") UUID repositoryId,
            @Param("serviceName") String serviceName,
            @Param("normalizedServiceName") String normalizedServiceName,
            @Param("actorId") UUID actorId,
            @Param("now") Instant now);

    int insertContract(
            @Param("row") EngineeringContractRow row,
            @Param("normalizedContractKey") String normalizedContractKey,
            @Param("actorId") UUID actorId,
            @Param("now") Instant now);

    List<CurrentPathChunkRow> currentPathChunks(
            @Param("repositoryId") UUID repositoryId, @Param("filePath") String filePath);

    List<EngineeringReviewRepositoryRow> reviewRepositories(
            @Param("targetRepositoryId") UUID targetRepositoryId,
            @Param("actorId") UUID actorId);

    List<EngineeringReviewContractRow> reviewContracts(
            @Param("targetRepositoryId") UUID targetRepositoryId,
            @Param("actorId") UUID actorId);

    int crossScopedKnowledgeCount(@Param("projectId") UUID projectId);

    int activeProjectCountForRepository(@Param("repositoryId") UUID repositoryId);
}
