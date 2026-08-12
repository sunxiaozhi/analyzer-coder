package com.analyzercoder.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义知识附件数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface KnowledgeAttachmentMapper {
    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @param originalName 上传附件的原始文件名
     * @param mediaType 附件或响应内容的 MIME 类型
     * @param sizeBytes 内容或附件占用的字节数
     * @param sha256 内容的 SHA-256 完整性摘要
     * @param storagePath 附件或产物在受控存储根目录下的路径
     * @param actorId 目标对象的唯一标识
     * @param createdAt 记录首次创建的时间点
     * @return 本次操作影响的记录数
     */
    int insert(
            @Param("id") UUID id,
            @Param("repositoryId") UUID repositoryId,
            @Param("originalName") String originalName,
            @Param("mediaType") String mediaType,
            @Param("sizeBytes") long sizeBytes,
            @Param("sha256") String sha256,
            @Param("storagePath") String storagePath,
            @Param("actorId") UUID actorId,
            @Param("createdAt") Instant createdAt);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param id 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    Map<String, Object> find(@Param("repositoryId") UUID repositoryId, @Param("id") UUID id);

    /**
     * 查询指定知识卡片当前版本的附件。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param cardId 目标对象的唯一标识
     * @param revision 知识内容的修订版本号
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> listForCard(
            @Param("repositoryId") UUID repositoryId,
            @Param("cardId") UUID cardId,
            @Param("revision") int revision);

    /**
     * 统计符合给定条件的记录数。
     *
     * @param cardId 目标对象的唯一标识
     * @param revision 知识内容的修订版本号
     * @return 符合条件的记录数
     */
    int countForRevision(@Param("cardId") UUID cardId, @Param("revision") int revision);

    /**
     * 统计指定知识修订版本附件占用的总字节数。
     *
     * @param cardId 目标对象的唯一标识
     * @param revision 知识内容的修订版本号
     * @return 本次操作影响的记录数
     */
    long totalBytesForRevision(@Param("cardId") UUID cardId, @Param("revision") int revision);

    /**
     * 创建并持久化一条新记录。
     *
     * @param cardId 目标对象的唯一标识
     * @param revision 知识内容的修订版本号
     * @param attachmentId 目标对象的唯一标识
     * @param position 记录在稳定排序中的位置
     * @return 本次操作影响的记录数
     */
    int insertRef(
            @Param("cardId") UUID cardId,
            @Param("revision") int revision,
            @Param("attachmentId") UUID attachmentId,
            @Param("position") int position);
}
