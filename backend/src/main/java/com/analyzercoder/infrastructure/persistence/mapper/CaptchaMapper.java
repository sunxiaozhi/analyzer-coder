package com.analyzercoder.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CaptchaMapper {
    int failureCount(@Param("username") String username);
    int recordFailure(@Param("username") String username);
    int clearFailures(@Param("username") String username);
    int insertChallenge(@Param("id") UUID id, @Param("username") String username,
        @Param("answerHash") String answerHash, @Param("expiresAt") Instant expiresAt);
    String findValidAnswerHash(@Param("id") UUID id, @Param("username") String username);
    int markUsed(@Param("id") UUID id);
}
