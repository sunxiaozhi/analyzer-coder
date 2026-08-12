package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import com.analyzercoder.infrastructure.persistence.model.RepositoryVersionRow;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义代码图谱产物数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface CodeGraphArtifactMapper {
    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    RepositoryVersionRow findRepositoryVersion(@Param("repositoryId") UUID repositoryId);

    /**
     * 将当前已发布图谱产物转为历史状态。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int retirePublished(@Param("repositoryId") UUID repositoryId);

    /**
     * 创建并持久化一条新记录。
     *
     * @param row 待持久化的数据库映射数据
     * @return 本次操作影响的记录数
     */
    int insertPublished(CodeGraphArtifactRow row);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param snapshotId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    CodeGraphArtifactRow findLatest(
            @Param("repositoryId") UUID repositoryId, @Param("snapshotId") UUID snapshotId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param snapshotId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    CodeGraphArtifactRow findPublished(
            @Param("repositoryId") UUID repositoryId, @Param("snapshotId") UUID snapshotId);
}
