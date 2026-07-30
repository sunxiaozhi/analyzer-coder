package com.analyzercoder.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GraphRetrievalMapper {
    @Select("""
        <script>
        SELECT DISTINCT c.id,c.snapshot_id,c.file_path,c.symbol_name,c.symbol_kind,
        c.start_line,c.end_line,c.content,c.content_hash,
        0.24 lexical_score,0.0 semantic_score
        FROM code_graph_edges g
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
        WHERE g.repo_id=#{repositoryId}
        ORDER BY c.id
        LIMIT #{limit}
        </script>
        """)
    List<Map<String, Object>> relatedCodeChunks(
        @Param("repositoryId") UUID repositoryId,
        @Param("symbols") List<String> symbols,
        @Param("limit") int limit
    );
}
