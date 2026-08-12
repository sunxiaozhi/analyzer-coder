package com.analyzercoder.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义仓库导入任务数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface RepositoryImportJobMapper {
    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param actorId 目标对象的唯一标识
     * @param credentialId 目标对象的唯一标识
     * @param sourceType 仓库、知识或证据的来源类型
     * @param name 对象的业务名称
     * @param url 通过协议与目标策略校验的资源地址
     * @param branch 待检出或记录的 Git 分支名称
     * @return 本次操作影响的记录数
     */
    int insert(
            @Param("id") UUID id,
            @Param("actorId") UUID actorId,
            @Param("credentialId") UUID credentialId,
            @Param("sourceType") String sourceType,
            @Param("name") String name,
            @Param("url") String url,
            @Param("branch") String branch);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param id 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    Map<String, Object> find(@Param("id") UUID id);

    /**
     * 按当前访问范围和筛选条件查询记录列表。
     *
     * @param actorId 目标对象的唯一标识
     * @param admin 是否以管理员权限范围执行查询
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> list(@Param("actorId") UUID actorId, @Param("admin") boolean admin);

    /**
     * 以排他方式领取一条待处理记录。
     *
     * @return 接口约定的操作结果
     */
    Map<String, Object> claim();

    /**
     * 记录异步流程某一步骤的状态与执行结果。
     *
     * @param id 目标对象的唯一标识
     * @param step 异步任务中的步骤名称
     * @return 本次操作影响的记录数
     */
    int step(@Param("id") UUID id, @Param("step") String step);

    /**
     * 将任务标记为成功并保存最终进度。
     *
     * @param id 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int succeed(@Param("id") UUID id, @Param("repositoryId") UUID repositoryId);

    /**
     * 将任务标记为失败并保存可展示的错误摘要。
     *
     * @param id 目标对象的唯一标识
     * @param error 供内部诊断使用的失败原因
     * @return 本次操作影响的记录数
     */
    int fail(@Param("id") UUID id, @Param("error") String error);

    /**
     * 请求取消任务；处理器将在安全检查点停止执行。
     *
     * @param id 目标对象的唯一标识
     * @param actorId 目标对象的唯一标识
     * @param admin 是否以管理员权限范围执行查询
     * @return 本次操作影响的记录数
     */
    int requestCancel(
            @Param("id") UUID id, @Param("actorId") UUID actorId, @Param("admin") boolean admin);

    /**
     * 取消指定索引任务并返回最新状态。
     *
     * @param id 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int cancel(@Param("id") UUID id);
}
