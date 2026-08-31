package com.analyzercoder.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.EngineeringProjectMapper;
import com.analyzercoder.infrastructure.persistence.model.CurrentPathChunkRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringContractRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringProjectRepositoryRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringProjectRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringReviewContractRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringReviewRepositoryRow;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineeringProjectServiceTest {
    private final EngineeringProjectMapper mapper = mock(EngineeringProjectMapper.class);
    private final CodeRepositoryStore repositories = mock(CodeRepositoryStore.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final AuthService auth = mock(AuthService.class);
    private final EngineeringProjectService service =
            new EngineeringProjectService(mapper, repositories, access, auth);
    private final AuthenticatedAccount actor =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "owner", "Owner", AccountRole.NORMAL, false, Instant.now());
    private final UUID providerId = UUID.randomUUID();
    private final UUID consumerId = UUID.randomUUID();
    private final UUID providerSnapshot = UUID.randomUUID();
    private final UUID consumerSnapshot = UUID.randomUUID();

    @BeforeEach
    void repositoriesExist() {
        when(repositories.findById(CodeRepositoryId.of(providerId)))
                .thenReturn(Optional.of(repository(providerId, providerSnapshot, "provider")));
        when(repositories.findById(CodeRepositoryId.of(consumerId)))
                .thenReturn(Optional.of(repository(consumerId, consumerSnapshot, "consumer")));
    }

    @Test
    void createsAProjectOnlyAfterBothContractPathsResolveInCurrentSnapshots() {
        AtomicReference<UUID> projectId = new AtomicReference<>();
        AtomicReference<EngineeringContractRow> contract = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            projectId.set(invocation.getArgument(0));
                            return 1;
                        })
                .when(mapper)
                .insertProject(any(), anyString(), anyString(), anyString(), any(), any());
        doAnswer(
                        invocation -> {
                            contract.set(invocation.getArgument(0));
                            return 1;
                        })
                .when(mapper)
                .insertContract(any(), anyString(), any(), any());
        when(mapper.currentPathChunks(providerId, "openapi/order.yaml"))
                .thenReturn(List.of(new CurrentPathChunkRow(providerSnapshot, "openapi/order.yaml", 1, "provider-hash")));
        when(mapper.currentPathChunks(consumerId, "src/order-client.ts"))
                .thenReturn(List.of(new CurrentPathChunkRow(consumerSnapshot, "src/order-client.ts", 1, "consumer-hash")));
        when(mapper.findById(any()))
                .thenAnswer(
                        invocation ->
                                projectId.get() == null
                                        ? null
                                        : new EngineeringProjectRow(
                                                projectId.get(),
                                                "Commerce",
                                                "真实边界",
                                                actor.id(),
                                                1,
                                                Instant.now(),
                                                Instant.now()));
        when(mapper.repositories(any()))
                .thenAnswer(
                        invocation ->
                                List.of(
                                        new EngineeringProjectRepositoryRow(
                                                projectId.get(), providerId, "Provider", "order-service"),
                                        new EngineeringProjectRepositoryRow(
                                                projectId.get(), consumerId, "Consumer", "web-service")));
        when(mapper.contracts(any()))
                .thenAnswer(invocation -> contract.get() == null ? List.of() : List.of(contract.get()));

        EngineeringProjectService.EngineeringProject result =
                service.create(
                        actor,
                        new EngineeringProjectService.ProjectInput(
                                "Commerce",
                                "真实边界",
                                null,
                                List.of(
                                        new EngineeringProjectService.MemberInput(providerId, "order-service"),
                                        new EngineeringProjectService.MemberInput(consumerId, "web-service")),
                                List.of(
                                        new EngineeringProjectService.ContractInput(
                                                null,
                                                "order-api-v1",
                                                "订单 API",
                                                providerId,
                                                consumerId,
                                                "openapi/order.yaml",
                                                "src/order-client.ts"))),
                        "127.0.0.1");

        assertThat(result.repositories()).hasSize(2);
        assertThat(result.contracts()).singleElement().satisfies(item -> assertThat(item.current()).isTrue());
        assertThat(contract.get().providerContentFingerprint()).hasSize(64);
        verify(access).require(actor, CodeRepositoryId.of(providerId), RepositoryPermission.MANAGE);
        verify(access).require(actor, CodeRepositoryId.of(consumerId), RepositoryPermission.MANAGE);
        verify(auth)
                .audit(
                        actor.id(),
                        null,
                        providerId,
                        "ENGINEERING_PROJECT_CREATED",
                        "SUCCESS",
                        "127.0.0.1");
    }

    @Test
    void refusesAContractNameWithoutCurrentCodeEvidence() {
        when(mapper.currentPathChunks(providerId, "openapi/missing.yaml"))
                .thenReturn(List.of());

        assertThatThrownBy(
                        () ->
                                service.create(
                                        actor,
                                        new EngineeringProjectService.ProjectInput(
                                                "Commerce",
                                                "",
                                                null,
                                                List.of(
                                                        new EngineeringProjectService.MemberInput(
                                                                providerId, "order-service"),
                                                        new EngineeringProjectService.MemberInput(
                                                                consumerId, "web-service")),
                                                List.of(
                                                        new EngineeringProjectService.ContractInput(
                                                                null,
                                                                "order-api-v1",
                                                                "订单 API",
                                                                providerId,
                                                                consumerId,
                                                                "openapi/missing.yaml",
                                                                "src/order-client.ts"))),
                                        "127.0.0.1"))
                .isInstanceOf(EngineeringProjectException.class)
                .extracting("code")
                .isEqualTo("ENGINEERING_CONTRACT_EVIDENCE_NOT_FOUND");
    }

    @Test
    void reviewTopologyReturnsOnlyFingerprintCurrentContractFactsFromTheMapperBoundary()
            throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        when(mapper.reviewRepositories(consumerId, actor.id()))
                .thenReturn(
                        List.of(
                                new EngineeringReviewRepositoryRow(
                                        projectId, providerId, "web-service")));
        when(mapper.reviewContracts(consumerId, actor.id()))
                .thenReturn(
                        List.of(
                                new EngineeringReviewContractRow(
                                        projectId,
                                        contractId,
                                        consumerId,
                                        "src/order-client.ts",
                                        providerId,
                                        "openapi/order.yaml",
                                        fingerprint("1:provider-hash"),
                                        consumerId,
                                        "src/order-client.ts",
                                        fingerprint("1:consumer-hash"))));
        when(mapper.currentPathChunks(providerId, "openapi/order.yaml"))
                .thenReturn(
                        List.of(
                                new CurrentPathChunkRow(
                                        providerSnapshot,
                                        "openapi/order.yaml",
                                        1,
                                        "provider-hash")));
        when(mapper.currentPathChunks(consumerId, "src/order-client.ts"))
                .thenReturn(
                        List.of(
                                new CurrentPathChunkRow(
                                        consumerSnapshot,
                                        "src/order-client.ts",
                                        1,
                                        "consumer-hash")));

        EngineeringProjectService.ReviewTopology result =
                service.reviewTopology(consumerId, actor.id());

        assertThat(result.repositories()).singleElement().satisfies(binding -> {
            assertThat(binding.sourceRepositoryId()).isEqualTo(providerId);
            assertThat(binding.targetServiceName()).isEqualTo("web-service");
            assertThat(binding.contracts()).singleElement().satisfies(contractBinding -> {
                assertThat(contractBinding.contractId()).isEqualTo(contractId);
                assertThat(contractBinding.current()).isTrue();
            });
        });

        when(mapper.currentPathChunks(providerId, "openapi/order.yaml"))
                .thenReturn(
                        List.of(
                                new CurrentPathChunkRow(
                                        providerSnapshot,
                                        "openapi/order.yaml",
                                        1,
                                        "provider-changed")));
        EngineeringProjectService.ReviewTopology stale =
                service.reviewTopology(consumerId, actor.id());
        assertThat(stale.repositories().get(0).contracts().get(0).current()).isFalse();
    }

    private static CodeRepository repository(UUID id, UUID snapshotId, String name) {
        Instant now = Instant.now();
        Path path = Path.of("build", name).toAbsolutePath();
        return new CodeRepository(
                CodeRepositoryId.of(id),
                name,
                path,
                RepositorySourceType.LOCAL_GIT,
                "main",
                "a".repeat(40),
                null,
                false,
                RepositorySnapshotId.of(snapshotId),
                path,
                path.resolve(".codegraph"),
                now,
                now,
                now,
                now);
    }

    private static String fingerprint(String material) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes()));
    }
}
