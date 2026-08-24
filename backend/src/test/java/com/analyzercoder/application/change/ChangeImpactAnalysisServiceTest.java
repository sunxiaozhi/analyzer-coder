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
                                                "backend/src/main/java/com/acme/application/AuthService.java → backend/src/main/java/com/acme/domain/User.java"))),
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
        assertThat(result.modules())
                .extracting(ChangeImpactAnalysisService.ModuleImpact::moduleId)
                .containsExactly("backend/application", "backend/domain");
        assertThat(result.dependencies()).hasSize(1);
        assertThat(result.risks()).extracting(ProjectArchitectureMapService.ArchitectureRisk::id)
                .containsExactly("boundary-1");
        assertThat(result.tests()).singleElement().satisfies(item -> {
            assertThat(item.existing()).isTrue();
            assertThat(item.filePath()).endsWith("AuthServiceTest.java");
        });
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
                UUID.randomUUID().toString(),
                0.9,
                0.8,
                0.7,
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
