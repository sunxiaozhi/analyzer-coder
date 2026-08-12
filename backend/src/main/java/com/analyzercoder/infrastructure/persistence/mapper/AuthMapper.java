package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.AccountSummaryRow;
import com.analyzercoder.infrastructure.persistence.model.AuthAccountRow;
import com.analyzercoder.infrastructure.persistence.model.AuthSessionRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义当前模块数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface AuthMapper {
    /**
     * 统计系统中的账户总数。
     *
     * @return 本次操作影响的记录数
     */
    int accountCount();

    /**
     * 按给定条件查询匹配数据。
     *
     * @param username 用于登录或远程认证的用户名
     * @return 接口约定的操作结果
     */
    AuthAccountRow findByUsername(@Param("username") String username);

    /**
     * 按唯一标识查询记录。
     *
     * @param id 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    AuthAccountRow findById(@Param("id") UUID id);

    /**
     * 查询账户列表并保持稳定排序。
     *
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<AccountSummaryRow> listAccounts();

    /**
     * 按状态、角色和关键词筛选账户列表。
     *
     * @param query 经过规范化的查询条件
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<AccountSummaryRow> listAccountsFiltered(@Param("query") String query);

    /**
     * 查询账户摘要及其角色、状态和最近活动信息。
     *
     * @param id 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    AccountSummaryRow summary(@Param("id") UUID id);

    /**
     * 统计当前处于启用状态的管理员数量。
     *
     * @return 本次操作影响的记录数
     */
    int enabledAdminCount();

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param username 用于登录或远程认证的用户名
     * @param displayName 面向用户展示的名称
     * @param passwordHash 由自适应算法生成的密码哈希
     * @param role 账户角色或会话消息角色
     * @param mustChange 账户下次登录是否必须修改密码
     * @param expiresAt 令牌、会话或凭据的失效时间
     * @param now 用于一致性判断的当前时间
     * @return 本次操作影响的记录数
     */
    int insertAccount(
            @Param("id") UUID id,
            @Param("username") String username,
            @Param("displayName") String displayName,
            @Param("passwordHash") String passwordHash,
            @Param("role") String role,
            @Param("mustChange") boolean mustChange,
            @Param("expiresAt") Instant expiresAt,
            @Param("now") Instant now);

    /**
     * 原子记录登录失败次数并按策略更新锁定状态。
     *
     * @param id 目标对象的唯一标识
     * @param failures 当前累计的连续失败次数
     * @param lockedUntil 账户锁定自动解除的时间点
     * @param now 用于一致性判断的当前时间
     * @return 本次操作影响的记录数
     */
    int recordLoginFailure(
            @Param("id") UUID id,
            @Param("failures") int failures,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("now") Instant now);

    /**
     * 记录登录成功并清除累计失败与锁定状态。
     *
     * @param id 目标对象的唯一标识
     * @param sourceIp 按可信代理规则解析的客户端来源地址
     * @param now 用于一致性判断的当前时间
     * @return 本次操作影响的记录数
     */
    int recordLoginSuccess(
            @Param("id") UUID id, @Param("sourceIp") String sourceIp, @Param("now") Instant now);

    /**
     * 校验旧凭据后更新账户密码。
     *
     * @param id 目标对象的唯一标识
     * @param passwordHash 由自适应算法生成的密码哈希
     * @param now 用于一致性判断的当前时间
     * @return 本次操作影响的记录数
     */
    int changePassword(
            @Param("id") UUID id,
            @Param("passwordHash") String passwordHash,
            @Param("now") Instant now);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param displayName 面向用户展示的名称
     * @param role 账户角色或会话消息角色
     * @param enabled 目标配置或账户是否启用
     * @param expectedVersion 执行乐观锁更新时预期的当前版本号
     * @param now 用于一致性判断的当前时间
     * @return 本次操作影响的记录数
     */
    int updateAccount(
            @Param("id") UUID id,
            @Param("displayName") String displayName,
            @Param("role") String role,
            @Param("enabled") boolean enabled,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);

    /**
     * 由授权管理员重置账户密码并要求下次登录修改。
     *
     * @param id 目标对象的唯一标识
     * @param passwordHash 由自适应算法生成的密码哈希
     * @param expiresAt 令牌、会话或凭据的失效时间
     * @param now 用于一致性判断的当前时间
     * @return 本次操作影响的记录数
     */
    int resetPassword(
            @Param("id") UUID id,
            @Param("passwordHash") String passwordHash,
            @Param("expiresAt") Instant expiresAt,
            @Param("now") Instant now);

    /**
     * 解除账户锁定并清除累计登录失败。
     *
     * @param id 目标对象的唯一标识
     * @param now 用于一致性判断的当前时间
     * @return 本次操作影响的记录数
     */
    int unlock(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @param now 用于一致性判断的当前时间
     * @return 本次操作影响的记录数
     */
    int updateLastRepository(
            @Param("id") UUID id,
            @Param("repositoryId") UUID repositoryId,
            @Param("now") Instant now);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param tokenHash 不可逆的会话或验证码令牌摘要
     * @return 接口约定的操作结果
     */
    AuthSessionRow findSession(@Param("tokenHash") String tokenHash);

    /**
     * 创建并持久化一条新记录。
     *
     * @param tokenHash 不可逆的会话或验证码令牌摘要
     * @param accountId 目标对象的唯一标识
     * @param csrfToken 与当前会话绑定的 CSRF 令牌
     * @param now 用于一致性判断的当前时间
     * @param expiresAt 令牌、会话或凭据的失效时间
     * @return 本次操作影响的记录数
     */
    int insertSession(
            @Param("tokenHash") String tokenHash,
            @Param("accountId") UUID accountId,
            @Param("csrfToken") String csrfToken,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    /**
     * 刷新认证会话的最后访问时间。
     *
     * @param tokenHash 不可逆的会话或验证码令牌摘要
     * @param now 用于一致性判断的当前时间
     * @return 本次操作影响的记录数
     */
    int touchSession(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /**
     * 删除符合给定条件的数据。
     *
     * @param tokenHash 不可逆的会话或验证码令牌摘要
     * @return 本次操作影响的记录数
     */
    int deleteSession(@Param("tokenHash") String tokenHash);

    /**
     * 删除符合给定条件的数据。
     *
     * @param accountId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteAccountSessions(@Param("accountId") UUID accountId);

    /**
     * 查询账户对指定仓库拥有的权限集合。
     *
     * @param accountId 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> permissions(@Param("accountId") UUID accountId);

    /**
     * 查询满足筛选条件的审计记录。
     *
     * @param limit 允许返回的最大记录数
     * @param offset 查询起始偏移量
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> audits(@Param("limit") int limit, @Param("offset") int offset);

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param actorId 目标对象的唯一标识
     * @param targetId 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @param eventType 审计日志记录的事件类型
     * @param result 外部进程执行得到的退出状态与输出
     * @param requestId 目标对象的唯一标识
     * @param sourceIp 按可信代理规则解析的客户端来源地址
     * @param createdAt 记录首次创建的时间点
     * @return 本次操作影响的记录数
     */
    int insertAudit(
            @Param("id") UUID id,
            @Param("actorId") UUID actorId,
            @Param("targetId") UUID targetId,
            @Param("repositoryId") UUID repositoryId,
            @Param("eventType") String eventType,
            @Param("result") String result,
            @Param("requestId") UUID requestId,
            @Param("sourceIp") String sourceIp,
            @Param("createdAt") Instant createdAt);
}
