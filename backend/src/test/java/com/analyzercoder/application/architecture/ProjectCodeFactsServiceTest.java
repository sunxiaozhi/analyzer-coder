package com.analyzercoder.application.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.application.intelligence.CodeGraphService;
import com.analyzercoder.application.repository.RepositoryCodeBrowserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectCodeFactsServiceTest {
    @Test
    void derivesStackResponsibilitiesGraphFactsAndSuggestionsWithoutMarkdown() {
        RepositoryCodeBrowserService.SnapshotFiles snapshot =
                new RepositoryCodeBrowserService.SnapshotFiles(
                        "snapshot-1",
                        "main",
                        "abc123",
                        List.of(
                                file("backend/src/main/java/demo/UserController.java", "java"),
                                file("backend/src/main/java/demo/UserService.java", "java"),
                                file("backend/src/main/java/demo/UserRepository.java", "java"),
                                file("frontend/src/views/UserView.vue", "vue"),
                                file("frontend/src/components/UserCard.vue", "vue"),
                                file("frontend/package.json", "json"),
                                file("backend/pom.xml", "xml"),
                                file("README.md", "markdown")));
        Map<String, String> contents =
                Map.of(
                        "frontend/package.json",
                        """
                        {"dependencies":{"vue":"3.5.0","pinia":"3.0.0"},
                         "devDependencies":{"vite":"7.0.0","typescript":"5.0.0"}}
                        """,
                        "backend/pom.xml",
                        """
                        <dependency><artifactId>spring-boot-starter-web</artifactId></dependency>
                        <dependency><artifactId>mybatis-spring-boot-starter</artifactId></dependency>
                        """);
        ProjectArchitectureMapService.ArchitectureMap architecture =
                new ProjectArchitectureMapService.ArchitectureMap(
                        UUID.randomUUID().toString(),
                        "snapshot-1",
                        "abc123",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        List.of(
                                node("backend", 3, "java"),
                                node("frontend", 2, "vue")),
                        List.of(
                                new ProjectArchitectureMapService.ArchitectureEdge(
                                        "frontend", "backend", "DEPENDS_ON", 9, List.of(), List.of())),
                        List.of(),
                        new ProjectArchitectureMapService.AnalysisCoverage(
                                5, 5, 0, 0, 0, false, List.of("完整扫描")));
        CodeGraphService.Artifact artifact =
                new CodeGraphService.Artifact(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "1.2.3",
                        "PUBLISHED",
                        "artifact",
                        240,
                        510);

        ProjectCodeFactsService.CodeFacts facts =
                ProjectCodeFactsService.analyze(
                        snapshot,
                        contents::get,
                        architecture,
                        artifact,
                        new ObjectMapper(),
                        Instant.parse("2026-01-02T00:00:00Z"));

        assertThat(facts.projectType()).isEqualTo("前后端分离 Web 应用");
        assertThat(facts.codeFileCount()).isEqualTo(5);
        assertThat(facts.technologies())
                .extracting(ProjectCodeFactsService.TechnologyFact::name)
                .contains("Spring Boot", "MyBatis", "Vue", "Pinia", "Vite", "Maven")
                .doesNotContain("README");
        assertThat(facts.fileCategories())
                .extracting(ProjectCodeFactsService.FileCategory::key)
                .contains("API", "SERVICE", "DATA", "VIEW", "COMPONENT");
        assertThat(facts.graph().symbolNodes()).isEqualTo(240);
        assertThat(facts.graph().dependencyEdges()).isEqualTo(1);
        assertThat(facts.graph().hotspots().get(0).module()).isEqualTo("backend");
        assertThat(facts.suggestions())
                .extracting(ProjectCodeFactsService.ProjectSuggestion::title)
                .contains("补齐最小测试保护网", "优先梳理图谱热点模块 backend");
        assertThat(facts.evidenceNotes()).allMatch(note -> !note.contains("提取 README"));
    }

    private static RepositoryCodeBrowserService.FileEntry file(String path, String language) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return new RepositoryCodeBrowserService.FileEntry(path, name, language, 100);
    }

    private static ProjectArchitectureMapService.ArchitectureNode node(
            String name, long files, String language) {
        return new ProjectArchitectureMapService.ArchitectureNode(
                name, name, name, "MODULE", files, files, language, null);
    }
}
