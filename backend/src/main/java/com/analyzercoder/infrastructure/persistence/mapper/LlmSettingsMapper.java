package com.analyzercoder.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义大模型设置数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface LlmSettingsMapper {
    /**
     * 查询指定供应商最近保存的大模型配置。
     *
     * @return 接口约定的操作结果
     */
    Map<String, Object> latestConfig();

    /**
     * 按标识与版本查询大模型配置。
     *
     * @param id 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    Map<String, Object> config(@Param("id") UUID id);

    /**
     * 查询指定大模型配置的全部历史版本。
     *
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> configVersions();

    /**
     * 计算指定供应商配置的下一个版本号。
     *
     * @return 本次操作影响的记录数
     */
    long nextConfigVersion();

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param cipherText 不含初始化向量的密文内容
     * @param iv 对称加密使用的随机初始化向量
     * @param secretDigest 用于检测密钥变化且不可逆的摘要值
     * @param algorithm 密钥加密或密码哈希所使用的算法标识
     * @param actorId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int insertSecret(
            @Param("id") UUID id,
            @Param("cipherText") String cipherText,
            @Param("iv") String iv,
            @Param("secretDigest") String secretDigest,
            @Param("algorithm") String algorithm,
            @Param("actorId") UUID actorId);

    /**
     * 查询指定大模型配置关联的加密密钥记录。
     *
     * @param id 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    Map<String, Object> secret(@Param("id") UUID id);

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param configVersion 大模型配置的历史版本号
     * @param name 对象的业务名称
     * @param providerType 大模型服务供应商类型
     * @param baseUrl 大模型兼容接口的基础地址
     * @param model 模型供应商使用的模型标识
     * @param connectTimeoutMs 建立网络连接允许的最大毫秒数
     * @param requestTimeoutMs 完整模型请求允许执行的最大毫秒数
     * @param maxOutputTokens 单次模型回答允许生成的最大令牌数
     * @param temperature 控制模型采样随机性的温度参数
     * @param streamingEnabled 模型接口是否启用流式响应
     * @param secretVersionId 目标对象的唯一标识
     * @param fingerprint 识别密钥或配置且不暴露原值的指纹
     * @param actorId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
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
            @Param("actorId") UUID actorId);

    /**
     * 创建并持久化一条新记录。
     *
     * @param configId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int insertRuntimeState(@Param("configId") UUID configId);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param name 对象的业务名称
     * @param providerType 大模型服务供应商类型
     * @param baseUrl 大模型兼容接口的基础地址
     * @param model 模型供应商使用的模型标识
     * @param connectTimeoutMs 建立网络连接允许的最大毫秒数
     * @param requestTimeoutMs 完整模型请求允许执行的最大毫秒数
     * @param maxOutputTokens 单次模型回答允许生成的最大令牌数
     * @param temperature 控制模型采样随机性的温度参数
     * @param streamingEnabled 模型接口是否启用流式响应
     * @param secretVersionId 目标对象的唯一标识
     * @param fingerprint 识别密钥或配置且不暴露原值的指纹
     * @param actorId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
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
            @Param("actorId") UUID actorId);

    /**
     * 重置模型配置的运行时健康状态和失败计数。
     *
     * @param configId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int resetRuntimeState(@Param("configId") UUID configId);

    /**
     * 查询当前配置的激活状态。
     *
     * @return 接口约定的操作结果
     */
    Map<String, Object> activation();

    /**
     * 激活指定配置，并使其成为运行时生效版本。
     *
     * @param configId 目标对象的唯一标识
     * @param actorId 目标对象的唯一标识
     * @param expectedVersion 执行乐观锁更新时预期的当前版本号
     * @return 本次操作影响的记录数
     */
    int activate(
            @Param("configId") UUID configId,
            @Param("actorId") UUID actorId,
            @Param("expectedVersion") long expectedVersion);

    /**
     * 停用当前生效的大模型配置。
     *
     * @param actorId 目标对象的唯一标识
     * @param expectedVersion 执行乐观锁更新时预期的当前版本号
     * @return 本次操作影响的记录数
     */
    int deactivate(@Param("actorId") UUID actorId, @Param("expectedVersion") long expectedVersion);

    /**
     * 查询当前运行时生效的大模型配置。
     *
     * @return 接口约定的操作结果
     */
    Map<String, Object> activeConfig();

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param actorId 目标对象的唯一标识
     * @param configId 目标对象的唯一标识
     * @param fingerprint 识别密钥或配置且不暴露原值的指纹
     * @param endpointHost 通过安全策略校验的服务主机名
     * @param model 模型供应商使用的模型标识
     * @param requestId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int insertCheck(
            @Param("id") UUID id,
            @Param("actorId") UUID actorId,
            @Param("configId") UUID configId,
            @Param("fingerprint") String fingerprint,
            @Param("endpointHost") String endpointHost,
            @Param("model") String model,
            @Param("requestId") UUID requestId);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param stage 异步流程当前执行阶段
     * @param stageResults 各执行阶段的结构化结果集合
     * @return 本次操作影响的记录数
     */
    int markCheckRunning(
            @Param("id") UUID id,
            @Param("stage") String stage,
            @Param("stageResults") String stageResults);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param stage 异步流程当前执行阶段
     * @param stageResults 各执行阶段的结构化结果集合
     * @return 本次操作影响的记录数
     */
    int updateCheckProgress(
            @Param("id") UUID id,
            @Param("stage") String stage,
            @Param("stageResults") String stageResults);

    /**
     * 完成连通性检查并保存耗时及可用性结果。
     *
     * @param id 目标对象的唯一标识
     * @param status 用于筛选或更新的目标状态
     * @param availability 连通性检查得到的服务可用状态
     * @param stage 异步流程当前执行阶段
     * @param stageResults 各执行阶段的结构化结果集合
     * @param errorCode 可供调用方稳定识别的错误码
     * @param errorSummary 已脱敏且适合展示的失败摘要
     * @param totalDurationMs 操作从开始到完成的总毫秒数
     * @param connectDurationMs 建立服务连接所耗费的毫秒数
     * @param firstTokenDurationMs 从请求到收到首个令牌的毫秒数
     * @return 本次操作影响的记录数
     */
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
            @Param("firstTokenDurationMs") Long firstTokenDurationMs);

    /**
     * 取消仍在执行的大模型连通性检查。
     *
     * @param id 目标对象的唯一标识
     * @param stageResults 各执行阶段的结构化结果集合
     * @return 本次操作影响的记录数
     */
    int cancelCheck(@Param("id") UUID id, @Param("stageResults") String stageResults);

    /**
     * 将服务重启前遗留的执行中检查统一标记为失败。
     *
     * @return 本次操作影响的记录数
     */
    int failInterruptedChecks();

    /**
     * 查询指定的大模型连通性检查记录。
     *
     * @param id 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    Map<String, Object> check(@Param("id") UUID id);

    /**
     * 查询最近一次通过且仍在有效期内的连通性检查。
     *
     * @param configId 目标对象的唯一标识
     * @param fingerprint 识别密钥或配置且不暴露原值的指纹
     * @param checkId 目标对象的唯一标识
     * @param notBefore 记录允许被领取或重试的最早时间
     * @return 本次操作影响的记录数
     */
    int recentAvailableCheck(
            @Param("configId") UUID configId,
            @Param("fingerprint") String fingerprint,
            @Param("checkId") UUID checkId,
            @Param("notBefore") Instant notBefore);

    /**
     * 将已通过的连通性检查结果应用到运行时状态。
     *
     * @param configId 目标对象的唯一标识
     * @param checkId 目标对象的唯一标识
     * @param availability 连通性检查得到的服务可用状态
     * @param errorCode 可供调用方稳定识别的错误码
     * @return 本次操作影响的记录数
     */
    int applyCheckToRuntime(
            @Param("configId") UUID configId,
            @Param("checkId") UUID checkId,
            @Param("availability") String availability,
            @Param("errorCode") String errorCode);

    /**
     * 记录运行时模型调用成功并重置失败状态。
     *
     * @param configId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int recordRuntimeSuccess(@Param("configId") UUID configId);

    /**
     * 记录运行时模型调用失败及连续失败次数。
     *
     * @param configId 目标对象的唯一标识
     * @param errorCode 可供调用方稳定识别的错误码
     * @param threshold 健康状态或熔断判断使用的失败阈值
     * @return 本次操作影响的记录数
     */
    int recordRuntimeFailure(
            @Param("configId") UUID configId,
            @Param("errorCode") String errorCode,
            @Param("threshold") int threshold);

    /**
     * 判断外部大模型能力是否已启用。
     *
     * @return 满足接口条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean externalModelEnabled();

    /**
     * 查询可用的向量模型配置列表。
     *
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> vectorModels();

    /**
     * 按标识查询向量模型配置。
     *
     * @param id 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    Map<String, Object> vectorModel(@Param("id") UUID id);

    /**
     * 查询当前激活的向量模型配置。
     *
     * @return 接口约定的操作结果
     */
    Map<String, Object> activeVectorModel();

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param name 对象的业务名称
     * @param providerType 大模型服务供应商类型
     * @param baseUrl 大模型兼容接口的基础地址
     * @param model 模型供应商使用的模型标识
     * @param dimension 向量模型输出的维度
     * @param requestTimeoutMs 完整模型请求允许执行的最大毫秒数
     * @param secretVersionId 目标对象的唯一标识
     * @param actorId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int insertVectorModel(
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("providerType") String providerType,
            @Param("baseUrl") String baseUrl,
            @Param("model") String model,
            @Param("dimension") int dimension,
            @Param("requestTimeoutMs") int requestTimeoutMs,
            @Param("secretVersionId") UUID secretVersionId,
            @Param("actorId") UUID actorId);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param name 对象的业务名称
     * @param providerType 大模型服务供应商类型
     * @param baseUrl 大模型兼容接口的基础地址
     * @param model 模型供应商使用的模型标识
     * @param dimension 向量模型输出的维度
     * @param requestTimeoutMs 完整模型请求允许执行的最大毫秒数
     * @param secretVersionId 目标对象的唯一标识
     * @param actorId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int updateVectorModel(
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("providerType") String providerType,
            @Param("baseUrl") String baseUrl,
            @Param("model") String model,
            @Param("dimension") int dimension,
            @Param("requestTimeoutMs") int requestTimeoutMs,
            @Param("secretVersionId") UUID secretVersionId,
            @Param("actorId") UUID actorId);

    /**
     * 激活指定向量模型，停用同用途的旧模型。
     *
     * @param id 目标对象的唯一标识
     * @param actorId 目标对象的唯一标识
     * @param expectedVersion 执行乐观锁更新时预期的当前版本号
     * @return 本次操作影响的记录数
     */
    int activateVectorModel(
            @Param("id") UUID id,
            @Param("actorId") UUID actorId,
            @Param("expectedVersion") long expectedVersion);
}
