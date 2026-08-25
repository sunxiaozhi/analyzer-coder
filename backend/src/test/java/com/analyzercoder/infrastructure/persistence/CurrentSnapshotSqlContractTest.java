package com.analyzercoder.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.infrastructure.persistence.mapper.GraphRetrievalMapper;
import com.analyzercoder.infrastructure.persistence.type.PostgresUuidTypeHandler;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class CurrentSnapshotSqlContractTest {

    @Test
    void changedMapperXmlRemainsLoadableByMyBatis() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        parseMapper(configuration, "mappers/CodeChunkMapper.xml");
        parseMapper(configuration, "mappers/IntelligenceMapper.xml");
        parseMapper(configuration, "mappers/VectorIndexQueryMapper.xml");
        parseMapper(configuration, "mappers/IndexJobMapper.xml");
    }

    @Test
    void codeChunkQueriesReuseTheCurrentSnapshotBoundary() throws Exception {
        String mapper = resource("mappers/CodeChunkMapper.xml");

        assertThat(mapper).contains("<sql id=\"currentSnapshot\">");
        assertThat(occurrences(mapper, "<include refid=\"currentSnapshot\"/>")).isEqualTo(2);
        assertThat(mapper)
                .contains("SELECT current_snapshot_id FROM repositories WHERE id=#{repositoryId}");
    }

    @Test
    void intelligenceAndKnowledgeQueriesRejectPreviousSnapshots() throws Exception {
        String intelligence = compact(resource("mappers/IntelligenceMapper.xml"));
        String vectors = compact(resource("mappers/VectorIndexQueryMapper.xml"));

        assertThat(occurrences(
                        intelligence,
                        "snapshot_id=(SELECTcurrent_snapshot_idFROMrepositoriesWHEREid=#{repositoryId})"))
                .isGreaterThanOrEqualTo(6);
        assertThat(intelligence)
                .contains(
                        "current_chunk.snapshot_id=(SELECTcurrent_snapshot_idFROMrepositoriesWHEREid=r.repo_id)");
        assertThat(vectors)
                .contains(
                        "current_chunk.snapshot_id=(SELECTcurrent_snapshot_idFROMrepositoriesWHEREid=ref.repo_id)");
    }

    @Test
    void structuralRetrievalUsesOnlyThePublishedGraphSnapshot() throws Exception {
        Select select =
                GraphRetrievalMapper.class
                        .getMethod("relatedCodeChunks", java.util.UUID.class, java.util.List.class, int.class)
                        .getAnnotation(Select.class);
        String sql = compact(String.join(" ", Arrays.asList(select.value())));

        assertThat(sql).contains("g.snapshot_id=r.current_snapshot_id");
        assertThat(sql).contains("c.snapshot_id=r.current_snapshot_id");
        assertThat(sql).contains("FROMheuristic_call_edgesg");
        assertThat(sql).doesNotContain("FROMcode_graph_edgesg");
    }

    @Test
    void embeddingCapabilityIsPersistedAndRequiredByRetrievalQueries() throws Exception {
        String migration = resource("db/migration/V5__embedding_retrieval_capability.sql");
        String intelligence = resource("mappers/IntelligenceMapper.xml");
        String vectors = resource("mappers/VectorIndexQueryMapper.xml");

        assertThat(migration).contains("CHARACTER_HASH", "SEMANTIC_EMBEDDING");
        assertThat(intelligence)
                .contains("e.retrieval_capability&lt;&gt;#{capability}")
                .contains("retrieval_capability=EXCLUDED.retrieval_capability");
        assertThat(vectors).contains("retrieval_capability");
    }

    @Test
    void knowledgePublicationReviewAndSourceVersionAreIndependent() throws Exception {
        String migration = resource("db/migration/V7__split_knowledge_states.sql");
        String intelligence = resource("mappers/IntelligenceMapper.xml");

        assertThat(migration)
                .contains("publication_status", "source_version_status", "review_status")
                .contains("DROP TRIGGER IF EXISTS trg_confirm_knowledge_code_version");
        assertThat(intelligence)
                .contains("publication_status='DRAFT',")
                .contains("review_status='UNREVIEWED'")
                .contains("c.review_status='APPROVED'")
                .contains("c.source_version_status&lt;&gt;'STALE'");
    }

    private static String resource(String name) throws Exception {
        try (InputStream input =
                CurrentSnapshotSqlContractTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as(name).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void parseMapper(Configuration configuration, String name) throws Exception {
        try (InputStream input =
                CurrentSnapshotSqlContractTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as(name).isNotNull();
            new XMLMapperBuilder(input, configuration, name, configuration.getSqlFragments()).parse();
        }
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", "");
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
