package com.analyzercoder.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LlmSettingsMapper {
    Map<String, Object> latestConfig();
    Map<String, Object> config(@Param("id") UUID id);
    List<Map<String, Object>> configVersions();
    long nextConfigVersion();

    int insertSecret(
        @Param("id") UUID id,
        @Param("cipherText") String cipherText,
        @Param("iv") String iv,
        @Param("secretDigest") String secretDigest,
        @Param("algorithm") String algorithm,
        @Param("actorId") UUID actorId
    );
    Map<String, Object> secret(@Param("id") UUID id);

    int insertConfig(
        @Param("id") UUID id,
        @Param("configVersion") long configVersion,
        @Param("name") String name,
        @Param("providerType") String providerType,
        @Param("baseUrl") String baseUrl,
        @Param("model") String model,
        @Param("connectTimeoutMs") int connectTimeoutMs,
        @Param("requestTimeoutMs") int requestTimeoutMs,
        @Param("maxOutputTokens") int maxOutputTokens,
        @Param("temperature") double temperature,
        @Param("streamingEnabled") boolean streamingEnabled,
        @Param("secretVersionId") UUID secretVersionId,
        @Param("fingerprint") String fingerprint,
        @Param("actorId") UUID actorId
    );
    int insertRuntimeState(@Param("configId") UUID configId);
    int updateConfig(
        @Param("id") UUID id,
        @Param("name") String name,
        @Param("providerType") String providerType,
        @Param("baseUrl") String baseUrl,
        @Param("model") String model,
        @Param("connectTimeoutMs") int connectTimeoutMs,
        @Param("requestTimeoutMs") int requestTimeoutMs,
        @Param("maxOutputTokens") int maxOutputTokens,
        @Param("temperature") double temperature,
        @Param("streamingEnabled") boolean streamingEnabled,
        @Param("secretVersionId") UUID secretVersionId,
        @Param("fingerprint") String fingerprint,
        @Param("actorId") UUID actorId
    );
    int resetRuntimeState(@Param("configId") UUID configId);

    Map<String, Object> activation();
    int activate(
        @Param("configId") UUID configId,
        @Param("actorId") UUID actorId,
        @Param("expectedVersion") long expectedVersion
    );
    int deactivate(@Param("actorId") UUID actorId, @Param("expectedVersion") long expectedVersion);
    Map<String, Object> activeConfig();

    int insertCheck(
        @Param("id") UUID id,
        @Param("actorId") UUID actorId,
        @Param("configId") UUID configId,
        @Param("fingerprint") String fingerprint,
        @Param("endpointHost") String endpointHost,
        @Param("model") String model,
        @Param("requestId") UUID requestId
    );
    int markCheckRunning(@Param("id") UUID id, @Param("stage") String stage, @Param("stageResults") String stageResults);
    int updateCheckProgress(@Param("id") UUID id, @Param("stage") String stage, @Param("stageResults") String stageResults);
    int completeCheck(
        @Param("id") UUID id,
        @Param("status") String status,
        @Param("availability") String availability,
        @Param("stage") String stage,
        @Param("stageResults") String stageResults,
        @Param("errorCode") String errorCode,
        @Param("errorSummary") String errorSummary,
        @Param("totalDurationMs") long totalDurationMs,
        @Param("connectDurationMs") Long connectDurationMs,
        @Param("firstTokenDurationMs") Long firstTokenDurationMs
    );
    int cancelCheck(@Param("id") UUID id, @Param("stageResults") String stageResults);
    int failInterruptedChecks();
    Map<String, Object> check(@Param("id") UUID id);
    int recentAvailableCheck(
        @Param("configId") UUID configId,
        @Param("fingerprint") String fingerprint,
        @Param("checkId") UUID checkId,
        @Param("notBefore") Instant notBefore
    );

    int applyCheckToRuntime(
        @Param("configId") UUID configId,
        @Param("checkId") UUID checkId,
        @Param("availability") String availability,
        @Param("errorCode") String errorCode
    );
    int recordRuntimeSuccess(@Param("configId") UUID configId);
    int recordRuntimeFailure(
        @Param("configId") UUID configId,
        @Param("errorCode") String errorCode,
        @Param("threshold") int threshold
    );
    boolean externalModelEnabled();

    List<Map<String, Object>> vectorModels();
    Map<String, Object> vectorModel(@Param("id") UUID id);
    Map<String, Object> activeVectorModel();
    int insertVectorModel(@Param("id") UUID id, @Param("name") String name,
        @Param("providerType") String providerType, @Param("baseUrl") String baseUrl,
        @Param("model") String model, @Param("dimension") int dimension,
        @Param("requestTimeoutMs") int requestTimeoutMs, @Param("secretVersionId") UUID secretVersionId,
        @Param("actorId") UUID actorId);
    int updateVectorModel(@Param("id") UUID id, @Param("name") String name,
        @Param("providerType") String providerType, @Param("baseUrl") String baseUrl,
        @Param("model") String model, @Param("dimension") int dimension,
        @Param("requestTimeoutMs") int requestTimeoutMs, @Param("secretVersionId") UUID secretVersionId,
        @Param("actorId") UUID actorId);
    int activateVectorModel(@Param("id") UUID id, @Param("actorId") UUID actorId,
        @Param("expectedVersion") long expectedVersion);
}
