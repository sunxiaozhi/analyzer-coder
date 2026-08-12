package com.analyzercoder.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义验证码数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface CaptchaMapper {
    /**
     * 统计指定来源在时间窗口内的验证码失败次数。
     *
     * @param username 用于登录或远程认证的用户名
     * @return 本次操作影响的记录数
     */
    int failureCount(@Param("username") String username);

    /**
     * 记录一次验证码校验失败及其来源信息。
     *
     * @param username 用于登录或远程认证的用户名
     * @return 本次操作影响的记录数
     */
    int recordFailure(@Param("username") String username);

    /**
     * 清除指定来源累计的验证码失败记录。
     *
     * @param username 用于登录或远程认证的用户名
     * @return 本次操作影响的记录数
     */
    int clearFailures(@Param("username") String username);

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param username 用于登录或远程认证的用户名
     * @param answerHash 用于检测回答内容变化的摘要值
     * @param expiresAt 令牌、会话或凭据的失效时间
     * @return 本次操作影响的记录数
     */
    int insertChallenge(
            @Param("id") UUID id,
            @Param("username") String username,
            @Param("answerHash") String answerHash,
            @Param("expiresAt") Instant expiresAt);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param id 目标对象的唯一标识
     * @param username 用于登录或远程认证的用户名
     * @return 接口约定的操作结果
     */
    String findValidAnswerHash(@Param("id") UUID id, @Param("username") String username);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int markUsed(@Param("id") UUID id);
}
