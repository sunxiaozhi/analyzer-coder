package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.TaskReviewRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 持久化幂等、版本化且终态不可覆盖的任务审查。 */
@Mapper
public interface TaskReviewMapper {
    int insertRunning(TaskReviewRow row);

    TaskReviewRow findByClientRequest(
            @Param("repositoryId") UUID repositoryId,
            @Param("createdBy") UUID createdBy,
            @Param("clientRequestId") UUID clientRequestId);

    TaskReviewRow findById(@Param("repositoryId") UUID repositoryId, @Param("id") UUID id);

    List<TaskReviewRow> findByRepository(
            @Param("repositoryId") UUID repositoryId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    int complete(
            @Param("repositoryId") UUID repositoryId,
            @Param("id") UUID id,
            @Param("baseCommit") String baseCommit,
            @Param("headCommit") String headCommit,
            @Param("worktreeDigest") String worktreeDigest,
            @Param("resultPayload") String resultPayload);

    int fail(
            @Param("repositoryId") UUID repositoryId,
            @Param("id") UUID id,
            @Param("baseCommit") String baseCommit,
            @Param("headCommit") String headCommit,
            @Param("worktreeDigest") String worktreeDigest,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);
}
