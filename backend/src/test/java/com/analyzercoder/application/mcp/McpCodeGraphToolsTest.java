package com.analyzercoder.application.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.branch.BranchReadContext;
import com.analyzercoder.application.branch.RepositoryBranchService;
import com.analyzercoder.application.intelligence.CodeGraphException;
import com.analyzercoder.application.intelligence.CodeGraphService;
import com.analyzercoder.application.intelligence.ManagedCodeGraphService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthenticatedAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpCodeGraphToolsTest {
    private final AccessControlService access = mock(AccessControlService.class);
    private final CodeRepositoryStore repositories = mock(CodeRepositoryStore.class);
    private final RepositoryBranchService branches = mock(RepositoryBranchService.class);
    private final CodeGraphService graphs = mock(CodeGraphService.class);
    private final ManagedCodeGraphService managed = mock(ManagedCodeGraphService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final McpCodeGraphTools tools =
            new McpCodeGraphTools(access, repositories, branches, graphs, managed, json);
    private final AuthenticatedAccount actor =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "test", "Test", AccountRole.NORMAL, false, null);

    @Test
    void discoveryFiltersInvisibleRepositoriesAndMarksGraphReadiness() {
        var visible = CodeRepository.create("Visible", Path.of("visible"));
        var hidden = CodeRepository.create("Hidden", Path.of("hidden"));
        var contentVersion = UUID.randomUUID();
        var branch =
                new RepositoryBranchService.Branch(
                        UUID.randomUUID(),
                        "main",
                        contentVersion,
                        "abc",
                        "READY",
                        null,
                        1,
                        "ACTIVE",
                        null);
        when(access.visibleRepositoryIds(actor)).thenReturn(List.of(visible.id().value()));
        when(repositories.findAll()).thenReturn(List.of(hidden, visible));
        when(branches.list(actor, visible.id().value())).thenReturn(List.of(branch));
        when(graphs.latestContentVersion(visible.id().value(), contentVersion))
                .thenReturn(
                        new CodeGraphService.Artifact(
                                UUID.randomUUID(),
                                visible.id().value(),
                                contentVersion,
                                "1.5.0",
                                "PUBLISHED",
                                "private-path",
                                1,
                                1));

        var result = tools.call("list_codegraph_scopes", json.createObjectNode(), actor);

        assertThat(result.path("totalProjects").asInt()).isEqualTo(1);
        assertThat(result.path("projects").get(0).path("name").asText()).isEqualTo("Visible");
        assertThat(
                        result.path("projects")
                                .get(0)
                                .path("branches")
                                .get(0)
                                .path("codegraphReady")
                                .asBoolean())
                .isTrue();
        assertThat(result.toString())
                .doesNotContain("private-path", hidden.id().value().toString());
    }

    @Test
    void graphQueryUsesPinnedContentVersionAndRejectsMissingArtifact() {
        UUID repo = UUID.randomUUID(), branch = UUID.randomUUID(), contentVersion = UUID.randomUUID();
        var context =
                new BranchReadContext(
                        UUID.randomUUID(),
                        repo,
                        branch,
                        "main",
                        contentVersion,
                        "abc",
                        null,
                        Instant.now().plusSeconds(60));
        var input =
                json.createObjectNode()
                        .put("repositoryId", repo.toString())
                        .put("branchId", branch.toString())
                        .put("query", "RefundService");
        when(branches.resolve(actor, repo, branch, null)).thenReturn(context);
        assertThatThrownBy(() -> tools.call("codegraph_search", input, actor))
                .isInstanceOf(CodeGraphException.class)
                .hasMessageContaining("尚未发布");
        verifyNoInteractions(managed);
        when(graphs.latestContentVersion(repo, contentVersion))
                .thenReturn(
                        new CodeGraphService.Artifact(
                                UUID.randomUUID(),
                                repo,
                                contentVersion,
                                "1.5.0",
                                "PUBLISHED",
                                "private-path",
                                1,
                                1));
        when(managed.readContentVersion(
                        repo, contentVersion, "query", List.of("-l", "20", "-j", "RefundService")))
                .thenReturn("[]");
        var result = tools.call("codegraph_search", input, actor);
        assertThat(result.path("context").path("contentVersion").asText())
                .isEqualTo(contentVersion.toString());
        assertThat(result.path("result").isArray()).isTrue();
        assertThat(result.toString()).doesNotContain("private-path");
        verify(managed)
                .readContentVersion(repo, contentVersion, "query", List.of("-l", "20", "-j", "RefundService"));
    }
}
