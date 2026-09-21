package com.analyzercoder.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BranchLifecycleSqlContractTest {
    @Test
    void migrationAndMappersKeepBranchIdentityAcrossHistoryAndMarkdown() throws Exception {
        String migration = resource("db/migration/V7__branch_lifecycle_and_provenance.sql");
        String markdown = resource("mappers/MarkdownKnowledgeSourceMapper.xml");
        String intelligence = resource("mappers/IntelligenceMapper.xml");

        assertThat(migration)
                .contains("tracking_status")
                .contains("repository_project_drafts")
                .contains("source_branch_id")
                .contains("repositories_default_branch_contentVersion")
                .contains("FOREIGN KEY(repo_id,branch_id)");
        assertThat(markdown)
                .contains("ON CONFLICT(repo_id,branch_id,file_path)")
                .contains("l.source_branch_id=s.branch_id")
                .contains("s.branch_id=l.source_branch_id");
        assertThat(intelligence)
                .contains("branch_id=#{branchId}")
                .contains("source_scope")
                .contains("content_version=#{contentVersion}");
    }

    private String resource(String path) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
