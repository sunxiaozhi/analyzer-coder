package com.analyzercoder.domain.chunk;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.Collection;
import java.util.List;

/** 定义代码片段的持久化端口，使领域逻辑不依赖具体存储技术。 */
public interface CodeChunkStore {

    /**
     * 以给定数据完整替换原有内容。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param chunks 待处理的代码片段数据
     */
    void replaceRepositoryChunks(CodeRepositoryId repositoryId, Collection<CodeChunk> chunks);

    /**
     * 以给定数据完整替换原有内容。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param paths 相对于仓库根目录的文件路径集合
     * @param chunks 待处理的代码片段数据
     * @param snapshotId 目标对象的唯一标识
     * @param commitSha 代码片段所属的 Git 提交号
     */
    void replaceRepositoryPaths(
            CodeRepositoryId repositoryId,
            Collection<String> paths,
            Collection<CodeChunk> chunks,
            com.analyzercoder.domain.repository.RepositorySnapshotId snapshotId,
            String commitSha);

    /**
     * 查询仓库最近成功写入索引的提交号。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    String latestIndexedCommit(CodeRepositoryId repositoryId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<CodeChunk> findByRepositoryId(CodeRepositoryId repositoryId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param limit 允许返回的最大记录数
     * @param offset 查询起始偏移量
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<CodeChunk> findByRepositoryId(CodeRepositoryId repositoryId, int limit, int offset);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param query 经过规范化的查询条件
     * @param limit 允许返回的最大记录数
     * @param offset 查询起始偏移量
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<CodeChunk> searchByRepositoryId(
            CodeRepositoryId repositoryId, String query, int limit, int offset);

    /**
     * 统计符合给定条件的记录数。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 符合条件的记录数
     */
    long countByRepositoryId(CodeRepositoryId repositoryId);

    /**
     * 统计符合给定条件的记录数。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param query 经过规范化的查询条件
     * @return 符合条件的记录数
     */
    long countSearchByRepositoryId(CodeRepositoryId repositoryId, String query);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     */
    void deleteByRepositoryId(CodeRepositoryId repositoryId);
}
