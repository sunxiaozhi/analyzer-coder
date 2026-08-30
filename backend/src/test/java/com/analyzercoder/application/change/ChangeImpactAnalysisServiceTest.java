package com.analyzercoder.application.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.architecture.ProjectArchitectureMapService;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.repository.RegisterRepositoryUseCase;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChangeImpactAnalysisServiceTest {

    @Test
    void buildsTraceableCandidatesDependenciesRisksAndTests() {
        RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
        IntelligenceService intelligence = mock(IntelligenceService.class);
        ProjectArchitectureMapService architecture = mock(ProjectArchitectureMapService.class);
        ChangeIntentParser intentParser = mock(ChangeIntentParser.class);
        ChangeImpactAnalysisService service =
                new ChangeImpactAnalysisService(
                        repositories, intelligence, architecture, intentParser);
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        RepositorySnapshotId snapshotId = RepositorySnapshotId.newId();
        when(repositories.get(repositoryId))
                .thenReturn(repository(repositoryId, snapshotId, false));
        when(intentParser.parse("限制登录失败次数", null)).thenReturn(intent("限制登录失败次数"));

        IntelligenceService.Evidence implementation =
                evidence(
                        repositoryId,
                        snapshotId,
                        "backend/src/main/java/com/acme/application/AuthService.java",
                        "AuthService",
                        "public class AuthService { void login() {} }");
        IntelligenceService.Evidence test =
                evidence(
                        repositoryId,
                        snapshotId,
                        "backend/src/test/java/com/acme/application/AuthServiceTest.java",
                        "AuthServiceTest",
                        "class AuthServiceTest { void rejectsInvalidPassword() {} }");
        when(intelligence.unifiedSearch(eq(repositoryId.value()), eq("限制登录失败次数"), eq(12)))
                .thenReturn(List.of(implementation));
        when(intelligence.unifiedSearch(
                        eq(repositoryId.value()), eq("限制登录失败次数 test 测试 spec"), eq(8)))
                .thenReturn(List.of(test));

        ProjectArchitectureMapService.ArchitectureRisk risk =
                new ProjectArchitectureMapService.ArchitectureRisk(
                        "boundary-1",
                        "HIGH",
                        "BOUNDARY",
                        "应用层越过领域边界",
                        "AuthService 直接依赖基础设施",
                        List.of("backend/application", "backend/domain"));
        ProjectArchitectureMapService.ArchitectureMap map =
                new ProjectArchitectureMapService.ArchitectureMap(
                        repositoryId.value().toString(),
                        snapshotId.value().toString(),
                        "abc123",
                        Instant.now(),
                        List.of(
                                node("backend/application", "application"),
                                node("backend/domain", "domain")),
                        List.of(
                                new ProjectArchitectureMapService.ArchitectureEdge(
                                        "backend/application",
                                        "backend/domain",
                                        "DEPENDS_ON",
                                        3,
                                        List.of(
                                                "backend/src/main/java/com/acme/application/AuthService.java → backend/src/main/java/com/acme/domain/User.java"),
                                        List.of(
                                                new ProjectArchitectureMapService.ArchitectureEvidenceSample(
                                                        "backend/src/main/java/com/acme/application/AuthService.java",
                                                        "backend/src/main/java/com/acme/domain/User.java",
                                                        snapshotId.value().toString(),
                                                        "b".repeat(64))))),
                        List.of(risk),
                        new ProjectArchitectureMapService.AnalysisCoverage(
                                12, 12, 0, 0, 0, false, List.of()));
        when(architecture.map(repositoryId)).thenReturn(map);

        ChangeImpactAnalysisService.ChangeImpactAnalysis result =
                service.analyze(repositoryId, "限制登录失败次数");

        assertThat(result.snapshotId()).isEqualTo(snapshotId.value());
        assertThat(result.intent().parserMode()).isEqualTo("MODEL");
        assertThat(result.retrievalQueries()).hasSize(2);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).moduleId()).isEqualTo("backend/application");
        assertThat(result.candidates().get(0).snapshotId()).isEqualTo(snapshotId.value());
        assertThat(result.candidates().get(0).contentHash()).hasSize(64);
        assertThat(result.candidates().get(0).provenance().snapshotId())
                .isEqualTo(snapshotId.value());
        assertThat(result.modules())
                .extracting(ChangeImpactAnalysisService.ModuleImpact::moduleId)
                .containsExactly("backend/application", "backend/domain");
        assertThat(result.dependencies()).hasSize(1);
        assertThat(result.dependencies().get(0).samples()).singleElement().satisfies(sample -> {
            assertThat(sample.snapshotId()).isEqualTo(snapshotId.value());
            assertThat(sample.contentHash()).hasSize(64);
        });
        assertThat(result.risks()).extracting(ProjectArchitectureMapService.ArchitectureRisk::id)
                .containsExactly("boundary-1");
        assertThat(result.tests()).singleElement().satisfies(item -> {
            assertThat(item.existing()).isTrue();
            assertThat(item.filePath()).endsWith("AuthServiceTest.java");
            assertThat(item.snapshotId()).isEqualTo(snapshotId.value());
            assertThat(item.contentHash()).hasSize(64);
        });
        assertThat(result.evidenceCoverage().label()).startsWith("证据覆盖");
        assertThat(result.unknowns())
                .extracting(ChangeImpactAnalysisService.AnalysisUnknown::code)
                .contains("DYNAMIC_BEHAVIOR")
                .doesNotContain("NO_DIRECT_EVIDENCE", "TEST_NOT_FOUND");
    }

    @Test
    void rejectsRepositoryWithoutPublishedSnapshot() {
        RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
        IntelligenceService intelligence = mock(IntelligenceService.class);
        ProjectArchitectureMapService architecture = mock(ProjectArchitectureMapService.class);
        ChangeIntentParser intentParser = mock(ChangeIntentParser.class);
        ChangeImpactAnalysisService service =
                new ChangeImpactAnalysisService(
                        repositories, intelligence, architecture, intentParser);
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        when(repositories.get(repositoryId)).thenReturn(repository(repositoryId, null, false));

        assertThatThrownBy(() -> service.analyze(repositoryId, "修改登录流程"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("项目快照");
    }

    @Test
    void excludesEvidenceFromAnOlderSnapshot() {
        RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
        IntelligenceService intelligence = mock(IntelligenceService.class);
        ProjectArchitectureMapService architecture = mock(ProjectArchitectureMapService.class);
        ChangeIntentParser intentParser = mock(ChangeIntentParser.class);
        ChangeImpactAnalysisService service =
                new ChangeImpactAnalysisService(
                        repositories, intelligence, architecture, intentParser);
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        RepositorySnapshotId currentSnapshot = RepositorySnapshotId.newId();
        RepositorySnapshotId previousSnapshot = RepositorySnapshotId.newId();
        when(repositories.get(repositoryId))
                .thenReturn(repository(repositoryId, currentSnapshot, false));
        when(intentParser.parse("修改登录流程", null)).thenReturn(intent("修改登录流程"));
        when(intelligence.unifiedSearch(
                        eq(repositoryId.value()), eq("修改登录流程"), eq(12)))
                .thenReturn(
                        List.of(
                                evidence(
                                        repositoryId,
                                        previousSnapshot,
                                        "backend/src/main/java/com/acme/AuthService.java",
                                        "AuthService",
                                        "class AuthService {}")));

        ChangeImpactAnalysisService.ChangeImpactAnalysis result =
                service.analyze(repositoryId, "修改登录流程");

        assertThat(result.snapshotId()).isEqualTo(currentSnapshot.value());
        assertThat(result.candidates()).isEmpty();
        assertThat(result.unknowns())
                .extracting(ChangeImpactAnalysisService.AnalysisUnknown::code)
                .contains("MIXED_SNAPSHOT_EVIDENCE_EXCLUDED", "NO_DIRECT_EVIDENCE");
    }

    @Test
    void excludesStaleTestEvidenceAndReportsTheCoverageGap() {
        RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
        IntelligenceService intelligence = mock(IntelligenceService.class);
        ProjectArchitectureMapService architecture = mock(ProjectArchitectureMapService.class);
        ChangeIntentParser intentParser = mock(ChangeIntentParser.class);
        ChangeImpactAnalysisService service =
                new ChangeImpactAnalysisService(
                        repositories, intelligence, architecture, intentParser);
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        RepositorySnapshotId currentSnapshot = RepositorySnapshotId.newId();
        RepositorySnapshotId previousSnapshot = RepositorySnapshotId.newId();
        when(repositories.get(repositoryId))
                .thenReturn(repository(repositoryId, currentSnapshot, false));
        when(intentParser.parse("修改登录流程", null)).thenReturn(intent("修改登录流程"));
        when(intelligence.unifiedSearch(
                        eq(repositoryId.value()), eq("修改登录流程"), eq(12)))
                .thenReturn(List.of(evidence(
                        repositoryId,
                        currentSnapshot,
                        "backend/src/main/java/com/acme/AuthService.java",
                        "AuthService",
                        "class AuthService {}")));
        when(intelligence.unifiedSearch(
                        eq(repositoryId.value()), eq("修改登录流程 test 测试 spec"), eq(8)))
                .thenReturn(List.of(evidence(
                        repositoryId,
                        previousSnapshot,
                        "backend/src/test/java/com/acme/AuthServiceTest.java",
                        "AuthServiceTest",
                        "class AuthServiceTest {}")));

        ChangeImpactAnalysisService.ChangeImpactAnalysis result =
                service.analyze(repositoryId, "修改登录流程");

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.tests()).singleElement().satisfies(item -> {
            assertThat(item.existing()).isFalse();
            assertThat(item.contentHash()).isNull();
        });
        assertThat(result.unknowns())
                .extracting(ChangeImpactAnalysisService.AnalysisUnknown::code)
                .contains("MIXED_SNAPSHOT_EVIDENCE_EXCLUDED", "TEST_NOT_FOUND");
    }

    @Test
    void knowledgeRetrievalDoesNotCreateMixedCodeSnapshotWarnings() {
        RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
        IntelligenceService intelligence = mock(IntelligenceService.class);
        ProjectArchitectureMapService architecture = mock(ProjectArchitectureMapService.class);
        ChangeIntentParser intentParser = mock(ChangeIntentParser.class);
        ChangeImpactAnalysisService service =
                new ChangeImpactAnalysisService(
                        repositories, intelligence, architecture, intentParser);
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        RepositorySnapshotId snapshotId = RepositorySnapshotId.newId();
        when(repositories.get(repositoryId))
                .thenReturn(repository(repositoryId, snapshotId, false));
        when(intentParser.parse("修改登录流程", null)).thenReturn(intent("修改登录流程"));
        IntelligenceService.Evidence knowledge =
                new IntelligenceService.Evidence(
                        repositoryId.value(),
                        "KNOWLEDGE",
                        null,
                        UUID.randomUUID(),
                        null,
                        "登录约束",
                        "knowledge://login-rule",
                        null,
                        "RULE",
                        null,
                        null,
                        "登录失败需要审计",
                        "f".repeat(32),
                        0.8,
                        0.8,
                        0,
                        "LEXICAL",
                        List.of("knowledge-keyword"),
                        List.of());
        when(intelligence.unifiedSearch(eq(repositoryId.value()), eq("修改登录流程"), eq(12)))
                .thenReturn(List.of(knowledge));
        when(intelligence.unifiedSearch(
                        eq(repositoryId.value()), eq("修改登录流程 test 测试 spec"), eq(8)))
                .thenReturn(List.of(knowledge));

        ChangeImpactAnalysisService.ChangeImpactAnalysis result =
                service.analyze(repositoryId, "修改登录流程");

        assertThat(result.candidates()).isEmpty();
        assertThat(result.unknowns())
                .extracting(ChangeImpactAnalysisService.AnalysisUnknown::code)
                .doesNotContain("MIXED_SNAPSHOT_EVIDENCE_EXCLUDED", "EVIDENCE_HASH_MISSING");
    }

    @Test
    void rejectsArchitectureFromAnotherSnapshot() {
        RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
        IntelligenceService intelligence = mock(IntelligenceService.class);
        ProjectArchitectureMapService architecture = mock(ProjectArchitectureMapService.class);
        ChangeIntentParser intentParser = mock(ChangeIntentParser.class);
        ChangeImpactAnalysisService service =
                new ChangeImpactAnalysisService(
                        repositories, intelligence, architecture, intentParser);
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        RepositorySnapshotId currentSnapshot = RepositorySnapshotId.newId();
        RepositorySnapshotId previousSnapshot = RepositorySnapshotId.newId();
        when(repositories.get(repositoryId))
                .thenReturn(repository(repositoryId, currentSnapshot, false));
        when(intentParser.parse("修改登录流程", null)).thenReturn(intent("修改登录流程"));
        when(architecture.map(repositoryId))
                .thenReturn(new ProjectArchitectureMapService.ArchitectureMap(
                        repositoryId.value().toString(),
                        previousSnapshot.value().toString(),
                        "old-commit",
                        Instant.now(),
                        List.of(),
                        List.of(),
                        List.of(),
                        new ProjectArchitectureMapService.AnalysisCoverage(
                                1, 1, 0, 0, 0, false, List.of())));

        ChangeImpactAnalysisService.ChangeImpactAnalysis result =
                service.analyze(repositoryId, "修改登录流程");

        assertThat(result.modules()).isEmpty();
        assertThat(result.dependencies()).isEmpty();
        assertThat(result.unknowns())
                .extracting(ChangeImpactAnalysisService.AnalysisUnknown::code)
                .contains("ARCHITECTURE_SNAPSHOT_MISMATCH", "ARCHITECTURE_UNAVAILABLE");
        assertThat(result.evidenceCoverage().level()).isEqualTo("LOW");
    }

    private static ProjectArchitectureMapService.ArchitectureNode node(
            String id, String label) {
        return new ProjectArchitectureMapService.ArchitectureNode(
                id, label, id, "MODULE", 5, 5, "java", null);
    }

    private static ChangeIntentParser.IntentInterpretation intent(String task) {
        return new ChangeIntentParser.IntentInterpretation(
                "MODEL",
                "test/model",
                null,
                "FEATURE",
                task,
                List.of("认证"),
                List.of("登录"),
                List.of(),
                List.of(),
                List.of("认证流程", "测试"),
                List.of(),
                List.of(task));
    }

    private static IntelligenceService.Evidence evidence(
            CodeRepositoryId repositoryId,
            RepositorySnapshotId snapshotId,
            String path,
            String symbol,
            String content) {
        return new IntelligenceService.Evidence(
                repositoryId.value(),
                "CODE_CHUNK",
                UUID.randomUUID(),
                null,
                snapshotId.value(),
                symbol,
                path,
                symbol,
                "CLASS",
                1,
                20,
                content,
                "a".repeat(64),
                0.9,
                0.8,
                0.7,
                "SEMANTIC_EMBEDDING",
                List.of("lexical", "vector"),
                List.of());
    }

    private static CodeRepository repository(
            CodeRepositoryId id, RepositorySnapshotId snapshotId, boolean dirty) {
        Instant now = Instant.now();
        return new CodeRepository(
                id,
                "demo",
                Path.of(".").toAbsolutePath(),
                RepositorySourceType.LOCAL_GIT,
                "main",
                "abc123",
                "digest",
                dirty,
                snapshotId,
                snapshotId == null ? null : Path.of(".").toAbsolutePath(),
                Path.of(".codegraph").toAbsolutePath(),
                snapshotId == null ? null : now,
                now,
                now,
                now);
    }
}
