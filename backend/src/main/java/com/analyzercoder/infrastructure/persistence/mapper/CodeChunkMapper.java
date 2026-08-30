package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.CodeChunkRow;
import com.analyzercoder.infrastructure.persistence.model.ModuleSymbolRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义代码片段数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface CodeChunkMapper {
    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteByRepositoryId(@Param("repositoryId") UUID repositoryId);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param paths 相对于仓库根目录的文件路径集合
     * @return 本次操作影响的记录数
     */
    int deleteByPaths(
            @Param("repositoryId") UUID repositoryId,
            @Param("paths") java.util.Collection<String> paths);

    /**
     * 将内容未变化的代码片段复用到新的提交版本。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param paths 相对于仓库根目录的文件路径集合
     * @param snapshotId 目标对象的唯一标识
     * @param commitSha 代码片段所属的 Git 提交号
     * @return 本次操作影响的记录数
     */
    int rebaseUnchanged(
            @Param("repositoryId") UUID repositoryId,
            @Param("paths") java.util.Collection<String> paths,
            @Param("snapshotId") UUID snapshotId,
            @Param("commitSha") String commitSha);

    /**
     * 查询仓库最近成功写入索引的提交号。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    String latestIndexedCommit(@Param("repositoryId") UUID repositoryId);

    /**
     * 创建并持久化一条新记录。
     *
     * @param rows 待批量持久化的数据库映射记录
     * @return 本次操作影响的记录数
     */
    int insertBatch(@Param("rows") List<CodeChunkRow> rows);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param query 经过规范化的查询条件
     * @param limit 允许返回的最大记录数
     * @param offset 查询起始偏移量
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<CodeChunkRow> find(
            @Param("repositoryId") UUID repositoryId,
            @Param("query") String query,
            @Param("limit") Integer limit,
            @Param("offset") Integer offset);

    List<CodeChunkRow> findByPath(
            @Param("repositoryId") UUID repositoryId, @Param("filePath") String filePath);

    List<ModuleSymbolRow> findModuleSymbols(
            @Param("repositoryId") UUID repositoryId,
            @Param("snapshotId") UUID snapshotId,
            @Param("modulePrefix") String modulePrefix,
            @Param("layerSegment") String layerSegment,
            @Param("rootOnly") boolean rootOnly,
            @Param("limit") int limit);

    /**
     * 统计符合给定条件的记录数。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param query 经过规范化的查询条件
     * @return 符合条件的记录数
     */
    long count(@Param("repositoryId") UUID repositoryId, @Param("query") String query);
}
