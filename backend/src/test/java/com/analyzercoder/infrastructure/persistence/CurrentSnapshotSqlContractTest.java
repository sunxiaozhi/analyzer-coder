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
        parseMapper(configuration, "mappers/KnowledgeHistoryMapper.xml");
        parseMapper(configuration, "mappers/VectorIndexQueryMapper.xml");
        parseMapper(configuration, "mappers/IndexJobMapper.xml");
        parseMapper(configuration, "mappers/TaskReviewMapper.xml");
        parseMapper(configuration, "mappers/KnowledgeDriftMapper.xml");
        parseMapper(configuration, "mappers/ProjectHealthMapper.xml");
    }

    @Test
    void codeChunkQueriesReuseTheCurrentSnapshotBoundary() throws Exception {
        String mapper = resource("mappers/CodeChunkMapper.xml");

        assertThat(mapper).contains("<sql id=\"currentSnapshot\">");
        assertThat(occurrences(mapper, "<include refid=\"currentSnapshot\"/>")).isEqualTo(3);
        assertThat(mapper)
                .contains("SELECT current_snapshot_id FROM repositories WHERE id=#{repositoryId}");
        assertThat(compact(mapper))
                .contains(
                        "<selectid=\"findByPath\"resultMap=\"row\">SELECT<includerefid=\"columns\"/>FROMcode_chunksWHERErepo_id=#{repositoryId}ANDfile_path=#{filePath}<includerefid=\"currentSnapshot\"/>");
    }

    @Test
    void intelligenceAndKnowledgeQueriesRejectPreviousSnapshots() throws Exception {
        String intelligence = compact(resource("mappers/IntelligenceMapper.xml"));
        String vectors = compact(resource("mappers/VectorIndexQueryMapper.xml"));

        assertThat(
                        occurrences(
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
                        .getMethod(
                                "relatedCodeChunks",
                                java.util.UUID.class,
                                java.util.List.class,
                                int.class)
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
                .contains("c.source_version_status NOT IN ('SUSPECT','STALE')");
    }

    @Test
    void taskReviewsAreIdempotentImmutableAndCompleteOnlyOnTheCapturedSnapshot() throws Exception {
        String migration = resource("db/migration/V10__task_reviews.sql");
        String mapper = compact(resource("mappers/TaskReviewMapper.xml"));

        assertThat(migration)
                .contains("CREATE TABLE task_reviews")
                .contains("uq_task_reviews_client_request")
                .contains("created_by,repo_id,client_request_id")
                .contains("trg_task_reviews_immutable")
                .contains("result_payload JSONB")
                .contains("model_config_id UUID");
        assertThat(mapper)
                .contains("ONCONFLICT(created_by,repo_id,client_request_id)DONOTHING")
                .contains("ANDstatus='RUNNING'")
                .contains(
                        "ANDsnapshot_id=(SELECTcurrent_snapshot_idFROMrepositoriesWHEREid=#{repositoryId})");
    }

    @Test
    void engineeringKnowledgeFieldsAreVersionedAndRestoresLoseTrustState() throws Exception {
        String migration = resource("db/migration/V9__engineering_knowledge.sql");
        String intelligence = resource("mappers/IntelligenceMapper.xml");
        String history = resource("mappers/KnowledgeHistoryMapper.xml");

        assertThat(migration)
                .contains(
                        "knowledge_kind",
                        "severity",
                        "enforcement",
                        "owner_account_id",
                        "scope_payload",
                        "obligations_payload",
                        "last_verified_snapshot_id",
                        "verification_note")
                .contains("'UNVERIFIED','CURRENT','SUSPECT','STALE'")
                .contains("NEW.scope_payload", "NEW.obligations_payload");
        assertThat(intelligence)
                .contains("CAST(#{scopePayload} AS jsonb)")
                .contains("CAST(#{obligationsPayload} AS jsonb)");
        assertThat(history)
                .contains("knowledge_kind=#{source.knowledgeKind}")
                .contains("publication_status='DRAFT'")
                .contains("review_status='UNREVIEWED'")
                .contains("source_version_status='UNVERIFIED'")
                .contains("last_verified_snapshot_id=NULL,verification_note=NULL");
        assertThat(resource("mappers/VectorIndexQueryMapper.xml"))
                .contains("source_version_status NOT IN ('SUSPECT','STALE')");
    }

    @Test
    void knowledgeDriftRemovesRepositoryWideTriggerAndPersistsEvidence() throws Exception {
        String migration = resource("db/migration/V11__knowledge_drift_audit.sql");
        String mapper = resource("mappers/KnowledgeDriftMapper.xml");

        assertThat(migration)
                .contains("DROP TRIGGER IF EXISTS trg_repository_knowledge_stale")
                .contains("CREATE TABLE knowledge_drift_events")
                .contains("reasons_payload JSONB")
                .contains("uq_knowledge_drift_automatic_snapshot");
        assertThat(mapper)
                .contains("source_version_status='SUSPECT'")
                .contains("source_version_status='CURRENT'")
                .contains("revision=#{expectedRevision}")
                .contains("ON CONFLICT DO NOTHING");
    }

    @Test
    void projectHealthUsesIndependentPersistedKnowledgeStates() throws Exception {
        String mapper = resource("mappers/ProjectHealthMapper.xml");

        assertThat(mapper)
                .contains("source_version_status='CURRENT'")
                .contains("source_version_status='SUSPECT'")
                .contains("source_version_status='STALE'")
                .contains("source_version_status='UNVERIFIED'")
                .contains("publication_status='PUBLISHED'")
                .contains("review_status='APPROVED'")
                .contains("enforcement='REQUIRED' AND owner_account_id IS NULL")
                .contains("review_status='UNREVIEWED'")
                .contains("WHERE repo_id=#{repositoryId} AND publication_status&lt;&gt;'ARCHIVED'");
    }

    @Test
    void indexExecutionModeAndFallbackReasonArePersisted() throws Exception {
        String migration = resource("db/migration/V8__index_execution_plan.sql");
        String mapper = resource("mappers/IndexJobMapper.xml");

        assertThat(migration)
                .contains("execution_mode", "fallback_reason")
                .contains("'FULL','INCREMENTAL'");
        assertThat(mapper)
                .contains("execution_mode", "fallback_reason")
                .contains("#{executionMode}", "#{fallbackReason}");
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
            new XMLMapperBuilder(input, configuration, name, configuration.getSqlFragments())
                    .parse();
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
