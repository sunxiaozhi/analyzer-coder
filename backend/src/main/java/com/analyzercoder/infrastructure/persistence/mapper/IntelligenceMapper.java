package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.KnowledgeCardRow;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义智能分析数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface IntelligenceMapper {
    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param query 经过规范化的查询条件
     * @param terms 经过分词和规范化的检索词集合
     * @param termCount 参与查询的规范化检索词数量
     * @param limit 允许返回的最大记录数
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> searchCodeKeyword(
            @Param("repositoryId") UUID repositoryId,
            @Param("query") String query,
            @Param("terms") List<String> terms,
            @Param("termCount") int termCount,
            @Param("limit") int limit);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param vector 模型生成并经过维度校验的向量值
     * @param model 模型供应商使用的模型标识
     * @param dimension 向量模型输出的维度
     * @param limit 允许返回的最大记录数
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> searchCodeVector(
            @Param("repositoryId") UUID repositoryId,
            @Param("vector") String vector,
            @Param("model") String model,
            @Param("dimension") int dimension,
            @Param("limit") int limit);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param query 经过规范化的查询条件
     * @param terms 经过分词和规范化的检索词集合
     * @param termCount 参与查询的规范化检索词数量
     * @param limit 允许返回的最大记录数
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> searchKnowledgeKeyword(
            @Param("repositoryId") UUID repositoryId,
            @Param("query") String query,
            @Param("terms") List<String> terms,
            @Param("termCount") int termCount,
            @Param("limit") int limit);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param vector 模型生成并经过维度校验的向量值
     * @param model 模型供应商使用的模型标识
     * @param dimension 向量模型输出的维度
     * @param limit 允许返回的最大记录数
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> searchKnowledgeVector(
            @Param("repositoryId") UUID repositoryId,
            @Param("vector") String vector,
            @Param("model") String model,
            @Param("dimension") int dimension,
            @Param("limit") int limit);

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @param accountId 目标对象的唯一标识
     * @param question 用户提交的自然语言问题
     * @param answer 待保存或校验的模型回答正文
     * @param snapshotId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int insertConversation(
            @Param("id") UUID id,
            @Param("threadId") UUID threadId,
            @Param("turnNo") int turnNo,
            @Param("repositoryId") UUID repositoryId,
            @Param("accountId") UUID accountId,
            @Param("clientRequestId") UUID clientRequestId,
            @Param("title") String title,
            @Param("question") String question,
            @Param("answer") String answer,
            @Param("snapshotId") UUID snapshotId,
            @Param("provider") String provider,
            @Param("evidenceStatus") String evidenceStatus,
            @Param("fallbackReason") String fallbackReason,
            @Param("answerPayload") String answerPayload);

    Map<String, Object> findConversationByRequest(
            @Param("repositoryId") UUID repositoryId,
            @Param("accountId") UUID accountId,
            @Param("clientRequestId") UUID clientRequestId);

    Map<String, Object> findConversation(
            @Param("id") UUID id,
            @Param("repositoryId") UUID repositoryId,
            @Param("accountId") UUID accountId);

    Map<String, Object> findThread(
            @Param("threadId") UUID threadId,
            @Param("repositoryId") UUID repositoryId,
            @Param("accountId") UUID accountId);

    List<Map<String, Object>> listThreadTurns(
            @Param("threadId") UUID threadId,
            @Param("repositoryId") UUID repositoryId,
            @Param("accountId") UUID accountId);

    Integer nextTurnNo(@Param("threadId") UUID threadId);

    Map<String, Object> lockThread(@Param("threadId") UUID threadId);

    List<Map<String, Object>> listConversations(
            @Param("repositoryId") UUID repositoryId,
            @Param("accountId") UUID accountId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    int renameConversation(
            @Param("id") UUID id,
            @Param("repositoryId") UUID repositoryId,
            @Param("accountId") UUID accountId,
            @Param("title") String title);

    int deleteConversation(
            @Param("id") UUID id,
            @Param("repositoryId") UUID repositoryId,
            @Param("accountId") UUID accountId);

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param conversationId 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @param sourceType 仓库、知识或证据的来源类型
     * @param chunkId 目标对象的唯一标识
     * @param knowledgeCardId 目标对象的唯一标识
     * @param title 知识卡片、会话或证据的显示标题
     * @param filePath 相对于仓库根目录的规范化文件路径
     * @param symbolName 代码符号的限定名称或显示名称
     * @param startLine 源码证据起始行号，从 1 开始
     * @param endLine 源码证据结束行号，包含该行
     * @param evidenceHash 用于确认引用证据未变化的摘要值
     * @param rank 证据在检索结果中的排序序号
     * @return 本次操作影响的记录数
     */
    int insertCitation(
            @Param("id") UUID id,
            @Param("conversationId") UUID conversationId,
            @Param("repositoryId") UUID repositoryId,
            @Param("sourceType") String sourceType,
            @Param("chunkId") UUID chunkId,
            @Param("knowledgeCardId") UUID knowledgeCardId,
            @Param("title") String title,
            @Param("filePath") String filePath,
            @Param("symbolName") String symbolName,
            @Param("startLine") Integer startLine,
            @Param("endLine") Integer endLine,
            @Param("evidenceHash") String evidenceHash,
            @Param("rank") int rank,
            @Param("citationPayload") String citationPayload);

    /**
     * 查询指定仓库和版本的代码图谱边。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> graphEdges(@Param("repositoryId") UUID repositoryId);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteGraphEdges(@Param("repositoryId") UUID repositoryId);

    /**
     * 查询与图谱节点关联的代码片段证据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> graphChunks(@Param("repositoryId") UUID repositoryId);

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @param snapshotId 目标对象的唯一标识
     * @param sourceChunkId 目标对象的唯一标识
     * @param targetChunkId 目标对象的唯一标识
     * @param sourceSymbol 代码图谱边的起始符号标识
     * @param targetSymbol 代码图谱边的目标符号标识
     * @return 本次操作影响的记录数
     */
    int insertGraphEdge(
            @Param("id") UUID id,
            @Param("repositoryId") UUID repositoryId,
            @Param("snapshotId") UUID snapshotId,
            @Param("sourceChunkId") UUID sourceChunkId,
            @Param("targetChunkId") UUID targetChunkId,
            @Param("sourceSymbol") String sourceSymbol,
            @Param("targetSymbol") String targetSymbol);

    /**
     * 查询仓库范围内可见的知识卡片。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param includeDraft 是否将草稿知识内容纳入查询
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<KnowledgeCardRow> cards(
            @Param("repositoryId") UUID repositoryId, @Param("includeDraft") boolean includeDraft);

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @param actorId 目标对象的唯一标识
     * @param title 知识卡片、会话或证据的显示标题
     * @param cardType 知识卡片的业务类型
     * @param content 待保存、索引或渲染的正文内容
     * @param tags 用于分类和筛选内容的标签集合
     * @param status 用于筛选或更新的目标状态
     * @return 本次操作影响的记录数
     */
    int insertCard(
            @Param("id") UUID id,
            @Param("repositoryId") UUID repositoryId,
            @Param("actorId") UUID actorId,
            @Param("title") String title,
            @Param("cardType") String cardType,
            @Param("content") String content,
            @Param("tags") String[] tags,
            @Param("status") String status);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @param actorId 目标对象的唯一标识
     * @param title 知识卡片、会话或证据的显示标题
     * @param cardType 知识卡片的业务类型
     * @param content 待保存、索引或渲染的正文内容
     * @param tags 用于分类和筛选内容的标签集合
     * @param status 用于筛选或更新的目标状态
     * @return 本次操作影响的记录数
     */
    int updateCard(
            @Param("id") UUID id,
            @Param("repositoryId") UUID repositoryId,
            @Param("actorId") UUID actorId,
            @Param("title") String title,
            @Param("cardType") String cardType,
            @Param("content") String content,
            @Param("tags") String[] tags,
            @Param("status") String status);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param chunkId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    Map<String, Object> findChunk(
            @Param("repositoryId") UUID repositoryId, @Param("chunkId") UUID chunkId);

    /**
     * 查询知识内容关联的代码引用。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param cardId 目标对象的唯一标识
     * @param revision 知识内容的修订版本号
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> codeReferences(
            @Param("repositoryId") UUID repositoryId,
            @Param("cardId") UUID cardId,
            @Param("revision") int revision);

    /**
     * 创建并持久化一条新记录。
     *
     * @param cardId 目标对象的唯一标识
     * @param revision 知识内容的修订版本号
     * @param position 记录在稳定排序中的位置
     * @param repositoryId 目标对象的唯一标识
     * @param snapshotId 目标对象的唯一标识
     * @param chunkId 目标对象的唯一标识
     * @param filePath 相对于仓库根目录的规范化文件路径
     * @param symbolName 代码符号的限定名称或显示名称
     * @param startLine 源码证据起始行号，从 1 开始
     * @param endLine 源码证据结束行号，包含该行
     * @param contentHash 用于内容去重和变更检测的摘要值
     * @return 本次操作影响的记录数
     */
    int insertCodeReference(
            @Param("cardId") UUID cardId,
            @Param("revision") int revision,
            @Param("position") int position,
            @Param("repositoryId") UUID repositoryId,
            @Param("snapshotId") UUID snapshotId,
            @Param("chunkId") UUID chunkId,
            @Param("filePath") String filePath,
            @Param("symbolName") String symbolName,
            @Param("startLine") Integer startLine,
            @Param("endLine") Integer endLine,
            @Param("contentHash") String contentHash);

    /**
     * 查询智能分析模块的运行设置。
     *
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> settings();

    /**
     * 新增或更新一项智能分析运行设置。
     *
     * @param key 设置项的稳定业务键
     * @param value 设置项经过校验后的值
     * @param actorId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int upsertSetting(
            @Param("key") String key, @Param("value") String value, @Param("actorId") UUID actorId);

    /**
     * 查询尚未生成向量的代码片段。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param model 模型供应商使用的模型标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> missingEmbeddings(
            @Param("repositoryId") UUID repositoryId,
            @Param("model") String model,
            @Param("dimension") int dimension);

    /**
     * 新增或更新代码片段的向量数据。
     *
     * @param chunkId 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @param model 模型供应商使用的模型标识
     * @param vector 模型生成并经过维度校验的向量值
     * @param contentHash 用于内容去重和变更检测的摘要值
     * @return 本次操作影响的记录数
     */
    int upsertEmbedding(
            @Param("chunkId") UUID chunkId,
            @Param("repositoryId") UUID repositoryId,
            @Param("model") String model,
            @Param("dimension") int dimension,
            @Param("vector") String vector,
            @Param("contentHash") String contentHash);

    /**
     * 查询尚未生成向量的知识内容。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param model 模型供应商使用的模型标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> missingKnowledgeEmbeddings(
            @Param("repositoryId") UUID repositoryId,
            @Param("model") String model,
            @Param("dimension") int dimension);

    /**
     * 新增或更新知识内容的向量数据。
     *
     * @param cardId 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @param revision 知识内容的修订版本号
     * @param model 模型供应商使用的模型标识
     * @param vector 模型生成并经过维度校验的向量值
     * @param contentHash 用于内容去重和变更检测的摘要值
     * @return 本次操作影响的记录数
     */
    int upsertKnowledgeEmbedding(
            @Param("cardId") UUID cardId,
            @Param("repositoryId") UUID repositoryId,
            @Param("revision") int revision,
            @Param("model") String model,
            @Param("dimension") int dimension,
            @Param("vector") String vector,
            @Param("contentHash") String contentHash);
}
