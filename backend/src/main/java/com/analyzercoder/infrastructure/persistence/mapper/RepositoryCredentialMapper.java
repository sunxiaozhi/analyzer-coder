package com.analyzercoder.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义仓库凭据数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface RepositoryCredentialMapper {
    /**
     * 按当前访问范围和筛选条件查询记录列表。
     *
     * @param actorId 目标对象的唯一标识
     * @param includeAll 是否忽略默认范围并包含全部可见记录
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> list(
            @Param("actorId") UUID actorId, @Param("includeAll") boolean includeAll);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param id 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    Map<String, Object> find(@Param("id") UUID id);

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param type 操作对象的业务类型
     * @param displayName 面向用户展示的名称
     * @param serverUrl 经过校验的代码图谱服务地址
     * @param username 用于登录或远程认证的用户名
     * @param cipherText 不含初始化向量的密文内容
     * @param iv 对称加密使用的随机初始化向量
     * @param digest 用于验证仓库工作区内容的摘要值
     * @param algorithm 密钥加密或密码哈希所使用的算法标识
     * @param maskedValue 隐藏敏感片段后的展示值
     * @param actorId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int insert(
            @Param("id") UUID id,
            @Param("type") String type,
            @Param("displayName") String displayName,
            @Param("serverUrl") String serverUrl,
            @Param("username") String username,
            @Param("cipherText") String cipherText,
            @Param("iv") String iv,
            @Param("digest") String digest,
            @Param("algorithm") String algorithm,
            @Param("maskedValue") String maskedValue,
            @Param("actorId") UUID actorId);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param type 操作对象的业务类型
     * @param displayName 面向用户展示的名称
     * @param serverUrl 经过校验的代码图谱服务地址
     * @param username 用于登录或远程认证的用户名
     * @param cipherText 不含初始化向量的密文内容
     * @param iv 对称加密使用的随机初始化向量
     * @param digest 用于验证仓库工作区内容的摘要值
     * @param algorithm 密钥加密或密码哈希所使用的算法标识
     * @param maskedValue 隐藏敏感片段后的展示值
     * @param actorId 目标对象的唯一标识
     * @param replaceSecret 是否以本次输入替换已有加密密钥
     * @return 本次操作影响的记录数
     */
    int update(
            @Param("id") UUID id,
            @Param("type") String type,
            @Param("displayName") String displayName,
            @Param("serverUrl") String serverUrl,
            @Param("username") String username,
            @Param("cipherText") String cipherText,
            @Param("iv") String iv,
            @Param("digest") String digest,
            @Param("algorithm") String algorithm,
            @Param("maskedValue") String maskedValue,
            @Param("actorId") UUID actorId,
            @Param("replaceSecret") boolean replaceSecret);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param status 用于筛选或更新的目标状态
     * @param validatedAt 凭据或配置最近验证通过的时间
     * @param error 供内部诊断使用的失败原因
     * @return 本次操作影响的记录数
     */
    int updateValidation(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("validatedAt") Instant validatedAt,
            @Param("error") String error);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param status 用于筛选或更新的目标状态
     * @param actorId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int updateStatus(
            @Param("id") UUID id, @Param("status") String status, @Param("actorId") UUID actorId);

    /**
     * 统计符合给定条件的记录数。
     *
     * @param id 目标对象的唯一标识
     * @return 符合条件的记录数
     */
    int countBindings(@Param("id") UUID id);

    /**
     * 删除符合给定条件的数据。
     *
     * @param id 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int delete(@Param("id") UUID id);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    Map<String, Object> findBound(@Param("repositoryId") UUID repositoryId);

    /**
     * 查询凭据当前关联的代码仓库。
     *
     * @param id 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> bindings(@Param("id") UUID id);

    /**
     * 将凭据绑定到指定代码仓库。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param credentialId 目标对象的唯一标识
     * @param actorId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int bind(
            @Param("repositoryId") UUID repositoryId,
            @Param("credentialId") UUID credentialId,
            @Param("actorId") UUID actorId);

    /**
     * 解除凭据与指定代码仓库的绑定关系。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int unbind(@Param("repositoryId") UUID repositoryId);
}
