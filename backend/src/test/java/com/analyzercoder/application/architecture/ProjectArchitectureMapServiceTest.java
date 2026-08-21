package com.analyzercoder.application.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.application.repository.RepositoryCodeBrowserService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectArchitectureMapServiceTest {

    @Test
    void extractsCrossModuleDependenciesCyclesAndBoundaryRisks() {
        String domain = "backend/src/main/java/com/acme/domain/Order.java";
        String infrastructure = "backend/src/main/java/com/acme/infrastructure/DbStore.java";
        String api = "frontend/src/api/orders.ts";
        String stores = "frontend/src/stores/orders.ts";
        String config = "backend/src/main/resources/application.yml";
        Map<String, String> contents =
                Map.of(
                        domain,
                        "package com.acme.domain;\nimport com.acme.infrastructure.DbStore;",
                        infrastructure,
                        "package com.acme.infrastructure; public class DbStore {}",
                        api,
                        "import { state } from '../stores/orders'; export const api = state;",
                        stores,
                        "import { api } from '../api/orders'; export const state = api;",
                        config,
                        "datasource: jdbc:postgresql://db.internal:5432/orders\npartner: http://partner.internal:8080/api");
        List<RepositoryCodeBrowserService.FileEntry> files =
                List.of(
                        file(domain, "java"),
                        file(infrastructure, "java"),
                        file(api, "typescript"),
                        file(stores, "typescript"),
                        file(config, "yaml"));
        UUID snapshotId = UUID.randomUUID();

        ProjectArchitectureMapService.ArchitectureMap result =
                ProjectArchitectureMapService.analyze(
                        CodeRepositoryId.newId(),
                        new RepositoryCodeBrowserService.SnapshotFiles(
                                snapshotId.toString(), "main", "abc123", files),
                        contents::get,
                        Instant.parse("2026-08-21T00:00:00Z"));

        assertThat(result.snapshotId()).isEqualTo(snapshotId.toString());
        assertThat(result.nodes())
                .extracting(ProjectArchitectureMapService.ArchitectureNode::id)
                .contains(
                        "$project",
                        "backend/domain",
                        "backend/infrastructure",
                        "frontend/api",
                        "frontend/stores",
                        "resource:postgresql:db.internal:5432");
        assertThat(result.edges())
                .filteredOn(edge -> "DEPENDS_ON".equals(edge.relation()))
                .extracting(
                        ProjectArchitectureMapService.ArchitectureEdge::source,
                        ProjectArchitectureMapService.ArchitectureEdge::target)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                "backend/domain", "backend/infrastructure"),
                        org.assertj.core.groups.Tuple.tuple("frontend/api", "frontend/stores"),
                        org.assertj.core.groups.Tuple.tuple("frontend/stores", "frontend/api"));
        assertThat(result.edges())
                .filteredOn(edge -> "CONNECTS_TO".equals(edge.relation()))
                .extracting(
                        ProjectArchitectureMapService.ArchitectureEdge::source,
                        ProjectArchitectureMapService.ArchitectureEdge::target)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                "$project", "resource:http_api:partner.internal:8080"));
        assertThat(result.risks())
                .extracting(ProjectArchitectureMapService.ArchitectureRisk::type)
                .contains("BOUNDARY", "CYCLE", "INSECURE_TRANSPORT");
        assertThat(result.coverage().analyzedFiles()).isEqualTo(4);
        assertThat(result.coverage().partial()).isFalse();
    }

    private static RepositoryCodeBrowserService.FileEntry file(String path, String language) {
        return new RepositoryCodeBrowserService.FileEntry(
                path, path.substring(path.lastIndexOf('/') + 1), language, 120);
    }
}
