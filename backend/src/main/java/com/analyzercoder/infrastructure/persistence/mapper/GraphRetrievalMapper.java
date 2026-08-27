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
            SELECT DISTINCT c.id,c.snapshot_id,c.file_path,c.symbol_name,c.symbol_kind,
            c.start_line,c.end_line,c.content,c.content_hash,
            0.24 lexical_score,0.0 semantic_score
            FROM heuristic_call_edges g
            JOIN repositories r ON r.id=g.repo_id AND g.snapshot_id=r.current_snapshot_id
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
            WHERE g.repo_id=#{repositoryId} AND c.snapshot_id=r.current_snapshot_id
            ORDER BY c.id
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> relatedCodeChunks(
            @Param("repositoryId") UUID repositoryId,
            @Param("symbols") List<String> symbols,
            @Param("limit") int limit);
}
