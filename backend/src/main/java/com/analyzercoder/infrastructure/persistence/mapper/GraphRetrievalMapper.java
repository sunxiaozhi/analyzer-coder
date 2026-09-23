package com.analyzercoder.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 查询索引阶段生成的启发式调用候选；该数据不属于 CodeGraph CLI 产物。 */
@Mapper
public interface GraphRetrievalMapper {
    /**
     * 查询与指定符号关系相连的代码片段。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param symbols 待写入或查询的代码符号集合
     * @param limit 允许返回的最大记录数
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    @Select(
            """
            <script>
            SELECT DISTINCT c.id,c.content_version,c.file_path,c.symbol_name,c.symbol_kind,
            c.start_line,c.end_line,c.content,c.content_hash,
            0.24 lexical_score,0.0 semantic_score
            FROM heuristic_call_edges g
            JOIN repositories r ON r.id=g.repo_id AND g.content_version=r.current_content_version
            JOIN code_chunks c ON(
              (c.id=g.source_chunk_id AND g.target_symbol IN
                <foreach collection="symbols" item="symbol" open="(" separator="," close=")">
                  #{symbol}
                </foreach>)
              OR
              (c.id=g.target_chunk_id AND g.source_symbol IN
                <foreach collection="symbols" item="symbol" open="(" separator="," close=")">
                  #{symbol}
                </foreach>)
            )
            WHERE g.repo_id=#{repositoryId} AND c.content_version=r.current_content_version
            ORDER BY c.id
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> relatedCodeChunks(
            @Param("repositoryId") UUID repositoryId,
            @Param("symbols") List<String> symbols,
            @Param("limit") int limit);

    /** Read one-hop call neighbors for concrete chunks in the same content version. */
    @Select(
            """
            <script>
            SELECT c.id,c.content_version,c.file_path,c.symbol_name,c.symbol_kind,
                   c.start_line,c.end_line,c.content,c.content_hash,
                   g.source_chunk_id,g.target_chunk_id,g.source_symbol,g.target_symbol,g.relation
            FROM heuristic_call_edges g
            JOIN code_chunks c ON c.id = CASE
              WHEN g.source_chunk_id IN
                <foreach collection="chunkIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              THEN g.target_chunk_id ELSE g.source_chunk_id END
              AND c.repo_id=g.repo_id AND c.content_version=g.content_version
            WHERE g.repo_id=#{repositoryId} AND g.content_version=#{contentVersion}
              AND (g.source_chunk_id IN
                <foreach collection="chunkIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                OR g.target_chunk_id IN
                <foreach collection="chunkIds" item="id" open="(" separator="," close=")">#{id}</foreach>)
            ORDER BY g.source_symbol,g.target_symbol,c.id
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> callNeighbors(
            @Param("repositoryId") UUID repositoryId,
            @Param("contentVersion") UUID contentVersion,
            @Param("chunkIds") List<UUID> chunkIds,
            @Param("limit") int limit);
}
