package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.KnowledgeCardRow;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IntelligenceMapper {
    List<Map<String, Object>> searchCodeKeyword(
        @Param("repositoryId") UUID repositoryId,
        @Param("query") String query,
        @Param("terms") List<String> terms,
        @Param("termCount") int termCount,
        @Param("limit") int limit
    );
    List<Map<String, Object>> searchCodeVector(
        @Param("repositoryId") UUID repositoryId,
        @Param("vector") String vector,
        @Param("model") String model,
        @Param("limit") int limit
    );
    List<Map<String, Object>> searchKnowledgeKeyword(
        @Param("repositoryId") UUID repositoryId,
        @Param("query") String query,
        @Param("terms") List<String> terms,
        @Param("termCount") int termCount,
        @Param("limit") int limit
    );
    List<Map<String, Object>> searchKnowledgeVector(
        @Param("repositoryId") UUID repositoryId,
        @Param("vector") String vector,
        @Param("model") String model,
        @Param("limit") int limit
    );
    int insertConversation(
        @Param("id") UUID id,
        @Param("repositoryId") UUID repositoryId,
        @Param("accountId") UUID accountId,
        @Param("question") String question,
        @Param("answer") String answer,
        @Param("snapshotId") UUID snapshotId
    );
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
        @Param("rank") int rank
    );
    List<Map<String, Object>> graphEdges(@Param("repositoryId") UUID repositoryId);
    int deleteGraphEdges(@Param("repositoryId") UUID repositoryId);
    List<Map<String, Object>> graphChunks(@Param("repositoryId") UUID repositoryId);
    int insertGraphEdge(
        @Param("id") UUID id,
        @Param("repositoryId") UUID repositoryId,
        @Param("snapshotId") UUID snapshotId,
        @Param("sourceChunkId") UUID sourceChunkId,
        @Param("targetChunkId") UUID targetChunkId,
        @Param("sourceSymbol") String sourceSymbol,
        @Param("targetSymbol") String targetSymbol
    );
    List<KnowledgeCardRow> cards(
        @Param("repositoryId") UUID repositoryId,
        @Param("includeDraft") boolean includeDraft
    );
    int insertCard(
        @Param("id") UUID id,
        @Param("repositoryId") UUID repositoryId,
        @Param("actorId") UUID actorId,
        @Param("title") String title,
        @Param("cardType") String cardType,
        @Param("content") String content,
        @Param("tags") String[] tags,
        @Param("status") String status
    );
    int updateCard(
        @Param("id") UUID id,
        @Param("repositoryId") UUID repositoryId,
        @Param("actorId") UUID actorId,
        @Param("title") String title,
        @Param("cardType") String cardType,
        @Param("content") String content,
        @Param("tags") String[] tags,
        @Param("status") String status
    );
    Map<String, Object> findChunk(
        @Param("repositoryId") UUID repositoryId,
        @Param("chunkId") UUID chunkId
    );
    List<Map<String, Object>> codeReferences(
        @Param("repositoryId") UUID repositoryId,
        @Param("cardId") UUID cardId,
        @Param("revision") int revision
    );
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
        @Param("contentHash") String contentHash
    );
    List<Map<String, Object>> settings();
    int upsertSetting(
        @Param("key") String key,
        @Param("value") String value,
        @Param("actorId") UUID actorId
    );
    List<Map<String, Object>> missingEmbeddings(
        @Param("repositoryId") UUID repositoryId,
        @Param("model") String model
    );
    int upsertEmbedding(
        @Param("chunkId") UUID chunkId,
        @Param("repositoryId") UUID repositoryId,
        @Param("model") String model,
        @Param("vector") String vector,
        @Param("contentHash") String contentHash
    );
    List<Map<String, Object>> missingKnowledgeEmbeddings(
        @Param("repositoryId") UUID repositoryId,
        @Param("model") String model
    );
    int upsertKnowledgeEmbedding(
        @Param("cardId") UUID cardId,
        @Param("repositoryId") UUID repositoryId,
        @Param("revision") int revision,
        @Param("model") String model,
        @Param("vector") String vector,
        @Param("contentHash") String contentHash
    );
}
