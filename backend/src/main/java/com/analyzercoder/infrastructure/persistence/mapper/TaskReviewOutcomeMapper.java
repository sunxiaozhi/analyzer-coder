package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.TaskReviewFeedbackRow;
import com.analyzercoder.infrastructure.persistence.model.TaskReviewOutcomeRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskReviewOutcomeMapper {
    int insertOutcome(TaskReviewOutcomeRow row);

    TaskReviewOutcomeRow findByClientRequest(
            @Param("reviewId") UUID reviewId,
            @Param("reportedBy") UUID reportedBy,
            @Param("clientRequestId") UUID clientRequestId);

    TaskReviewOutcomeRow findById(
            @Param("repositoryId") UUID repositoryId,
            @Param("reviewId") UUID reviewId,
            @Param("id") UUID id);

    List<TaskReviewOutcomeRow> findByReview(
            @Param("repositoryId") UUID repositoryId,
            @Param("reviewId") UUID reviewId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    int insertFeedback(TaskReviewFeedbackRow row);

    List<TaskReviewFeedbackRow> feedback(@Param("outcomeId") UUID outcomeId);
}
