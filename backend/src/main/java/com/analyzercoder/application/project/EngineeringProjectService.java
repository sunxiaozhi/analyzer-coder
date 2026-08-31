package com.analyzercoder.application.project;

import com.analyzercoder.application.knowledge.RepositoryGlobMatcher;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.infrastructure.persistence.mapper.EngineeringProjectMapper;
import com.analyzercoder.infrastructure.persistence.model.CurrentPathChunkRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringContractRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringProjectRepositoryRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringProjectRow;
import com.analyzercoder.infrastructure.persistence.model.EngineeringReviewContractRow;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理多仓工程边界，并只暴露带当前代码证据的服务与契约关系。 */
@Service
public class EngineeringProjectService {
    private static final int MAX_MEMBERS = 30;
    private static final int MAX_CONTRACTS = 50;

    private final EngineeringProjectMapper mapper;
    private final CodeRepositoryStore repositories;
    private final AccessControlService access;
    private final AuthService auth;

    public EngineeringProjectService(
            EngineeringProjectMapper mapper,
            CodeRepositoryStore repositories,
            AccessControlService access,
            AuthService auth) {
        this.mapper = mapper;
        this.repositories = repositories;
        this.access = access;
        this.auth = auth;
    }

    public List<EngineeringProject> list(AuthenticatedAccount actor) {
        return mapper.listVisible(actor.id(), actor.isSuperAdmin()).stream()
                .map(this::view)
                .toList();
    }

    public EngineeringProject get(AuthenticatedAccount actor, UUID id) {
        EngineeringProjectRow row = mapper.findVisible(id, actor.id(), actor.isSuperAdmin());
        if (row == null) {
            throw new EngineeringProjectException("ENGINEERING_PROJECT_NOT_FOUND", "工程项目不存在或不可见");
        }
        return view(row);
    }

    @Transactional
    public EngineeringProject create(
            AuthenticatedAccount actor, ProjectInput requested, String sourceIp) {
        ValidatedProject input = validate(requested, Map.of());
        requireManage(actor, input.repositories().stream().map(Member::repositoryId).toList());
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        mapper.insertProject(
                id,
                input.name(),
                normalized(input.name()),
                input.description(),
                actor.id(),
                now);
        persistTopology(id, actor.id(), input, now);
        auth.audit(
                actor.id(), null, input.repositories().get(0).repositoryId(),
                "ENGINEERING_PROJECT_CREATED", "SUCCESS", sourceIp);
        return view(required(id));
    }

    @Transactional
    public EngineeringProject update(
            AuthenticatedAccount actor,
            UUID id,
            ProjectInput requested,
            String sourceIp) {
        EngineeringProjectRow existing = required(id);
        List<EngineeringProjectRepositoryRow> oldMembers = mapper.repositories(id);
        List<EngineeringContractRow> oldContracts = mapper.contracts(id);
        requireManage(actor, oldMembers.stream().map(EngineeringProjectRepositoryRow::repositoryId).toList());
        ValidatedProject input =
                validate(
                        requested,
                        oldContracts.stream()
                                .collect(Collectors.toMap(EngineeringContractRow::id, Function.identity())));
        requireManage(actor, input.repositories().stream().map(Member::repositoryId).toList());
        if (mapper.crossScopedKnowledgeCount(id) > 0) {
            requireStableReferencedTopology(oldMembers, oldContracts, input);
        }
        if (requested.expectedVersion() == null
                || mapper.updateProject(
                                id,
                                input.name(),
                                normalized(input.name()),
                                input.description(),
                                requested.expectedVersion(),
                                Instant.now())
                        != 1) {
            throw new EngineeringProjectException(
                    "ENGINEERING_PROJECT_VERSION_CONFLICT", "工程项目已被其他操作修改，请刷新后重试");
        }
        mapper.deleteContracts(id);
        mapper.deleteRepositories(id);
        persistTopology(id, actor.id(), input, Instant.now());
        auth.audit(
                actor.id(), null, input.repositories().get(0).repositoryId(),
                "ENGINEERING_PROJECT_UPDATED", "SUCCESS", sourceIp);
        return view(required(id));
    }

    @Transactional
    public void delete(
            AuthenticatedAccount actor,
            UUID id,
            long expectedVersion,
            String sourceIp) {
        EngineeringProjectRow existing = required(id);
        List<UUID> repositoryIds =
                mapper.repositories(id).stream()
                        .map(EngineeringProjectRepositoryRow::repositoryId)
                        .toList();
        requireManage(actor, repositoryIds);
        if (mapper.crossScopedKnowledgeCount(id) > 0) {
            throw new EngineeringProjectException(
                    "ENGINEERING_PROJECT_IN_USE", "仍有知识使用跨仓库 Scope，请先清理仓库、服务或契约范围");
        }
        if (existing.version() != expectedVersion
                || mapper.softDelete(id, expectedVersion, Instant.now()) != 1) {
            throw new EngineeringProjectException(
                    "ENGINEERING_PROJECT_VERSION_CONFLICT", "工程项目已被其他操作修改，请刷新后重试");
        }
        auth.audit(
                actor.id(), null, repositoryIds.isEmpty() ? null : repositoryIds.get(0),
                "ENGINEERING_PROJECT_DELETED", "SUCCESS", sourceIp);
    }

    /** Task Review 使用；SQL 已限制源仓库及契约两端必须对创建者可见。 */
    public ReviewTopology reviewTopology(UUID targetRepositoryId, UUID actorId) {
        List<EngineeringReviewContractRow> rows = mapper.reviewContracts(targetRepositoryId, actorId);
        Map<UUID, List<ContractBinding>> contractsByProject = new LinkedHashMap<>();
        for (EngineeringReviewContractRow row : rows) {
            boolean current =
                    Objects.equals(
                                    row.providerContentFingerprint(),
                                    currentEvidence(
                                                    row.providerRepositoryId(),
                                                    row.providerEvidencePath())
                                            .fingerprint())
                            && Objects.equals(
                                    row.consumerContentFingerprint(),
                                    currentEvidence(
                                                    row.consumerRepositoryId(),
                                                    row.consumerEvidencePath())
                                            .fingerprint());
            contractsByProject
                    .computeIfAbsent(row.projectId(), ignored -> new ArrayList<>())
                    .add(
                            new ContractBinding(
                                    row.contractId(), row.targetEvidencePath(), current));
        }
        List<RepositoryBinding> repositories =
                mapper.reviewRepositories(targetRepositoryId, actorId).stream()
                        .map(
                                row ->
                                        new RepositoryBinding(
                                                row.projectId(),
                                                row.sourceRepositoryId(),
                                                row.targetServiceName(),
                                                contractsByProject.getOrDefault(
                                                        row.projectId(), List.of())))
                        .toList();
        return new ReviewTopology(repositories);
    }

    public void requireRepositoryNotLinked(UUID repositoryId) {
        if (mapper.activeProjectCountForRepository(repositoryId) > 0) {
            throw new EngineeringProjectException(
                    "ENGINEERING_PROJECT_IN_USE", "仓库仍属于跨仓工程项目，请先解除工程项目关联");
        }
    }

    private EngineeringProject view(EngineeringProjectRow row) {
        List<ProjectRepository> members =
                mapper.repositories(row.id()).stream()
                        .map(
                                item ->
                                        new ProjectRepository(
                                                item.repositoryId(),
                                                item.repositoryName(),
                                                item.serviceName()))
                        .toList();
        List<ContractView> contracts =
                mapper.contracts(row.id()).stream()
                        .map(
                                item ->
                                        new ContractView(
                                                item.id(),
                                                item.contractKey(),
                                                item.name(),
                                                item.providerRepositoryId(),
                                                item.consumerRepositoryId(),
                                                item.providerEvidencePath(),
                                                item.consumerEvidencePath(),
                                                Objects.equals(
                                                        item.providerContentFingerprint(),
                                                        currentEvidence(
                                                                        item.providerRepositoryId(),
                                                                        item.providerEvidencePath())
                                                                .fingerprint()),
                                                Objects.equals(
                                                        item.consumerContentFingerprint(),
                                                        currentEvidence(
                                                                        item.consumerRepositoryId(),
                                                                        item.consumerEvidencePath())
                                                                .fingerprint())))
                        .toList();
        return new EngineeringProject(
                row.id(),
                row.name(),
                row.description(),
                row.version(),
                members,
                contracts,
                row.createdAt(),
                row.updatedAt());
    }

    private ValidatedProject validate(
            ProjectInput requested, Map<UUID, EngineeringContractRow> existingContracts) {
        Objects.requireNonNull(requested, "工程项目请求不能为空");
        String name = required(requested.name(), 120, "工程项目名称");
        String description = optional(requested.description(), 500, "工程项目说明");
        List<MemberInput> requestedMembers =
                requested.repositories() == null ? List.of() : List.copyOf(requested.repositories());
        if (requestedMembers.size() < 2 || requestedMembers.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("工程项目必须包含 2–" + MAX_MEMBERS + " 个仓库");
        }
        LinkedHashMap<UUID, Member> members = new LinkedHashMap<>();
        LinkedHashSet<String> serviceNames = new LinkedHashSet<>();
        for (MemberInput requestedMember : requestedMembers) {
            if (requestedMember == null || requestedMember.repositoryId() == null) {
                throw new IllegalArgumentException("工程项目仓库不能为空");
            }
            String serviceName = normalizedIdentifier(requestedMember.serviceName(), "服务名");
            if (members.containsKey(requestedMember.repositoryId())
                    || !serviceNames.add(serviceName)) {
                throw new IllegalArgumentException("工程项目中的仓库和服务名必须唯一");
            }
            CodeRepository repository = repository(requestedMember.repositoryId());
            if (repository.currentSnapshotId() == null) {
                throw new EngineeringProjectException(
                        "ENGINEERING_PROJECT_SNAPSHOT_REQUIRED",
                        repository.name() + " 尚未发布当前快照，不能建立跨仓事实");
            }
            members.put(
                    requestedMember.repositoryId(),
                    new Member(requestedMember.repositoryId(), serviceName));
        }
        List<ContractInput> requestedContracts =
                requested.contracts() == null ? List.of() : List.copyOf(requested.contracts());
        if (requestedContracts.size() > MAX_CONTRACTS) {
            throw new IllegalArgumentException("工程项目契约最多允许 " + MAX_CONTRACTS + " 项");
        }
        LinkedHashSet<String> contractKeys = new LinkedHashSet<>();
        LinkedHashSet<UUID> contractIds = new LinkedHashSet<>();
        List<ValidatedContract> contracts = new ArrayList<>();
        for (ContractInput contract : requestedContracts) {
            if (contract == null) {
                throw new IllegalArgumentException("契约不能为空");
            }
            String key = normalizedIdentifier(contract.contractKey(), "契约标识");
            if (!contractKeys.add(key)) {
                throw new IllegalArgumentException("工程项目中的契约标识必须唯一");
            }
            if (!members.containsKey(contract.providerRepositoryId())
                    || !members.containsKey(contract.consumerRepositoryId())
                    || Objects.equals(
                            contract.providerRepositoryId(), contract.consumerRepositoryId())) {
                throw new IllegalArgumentException("契约提供方和消费方必须是项目中的两个不同仓库");
            }
            UUID contractId = contract.id() == null ? UUID.randomUUID() : contract.id();
            if (!contractIds.add(contractId)
                    || (!existingContracts.isEmpty()
                            && contract.id() != null
                            && !existingContracts.containsKey(contract.id()))) {
                throw new IllegalArgumentException("契约 ID 不属于当前工程项目或发生重复");
            }
            Evidence provider =
                    requireEvidence(
                            contract.providerRepositoryId(), contract.providerEvidencePath(), "提供方");
            Evidence consumer =
                    requireEvidence(
                            contract.consumerRepositoryId(), contract.consumerEvidencePath(), "消费方");
            contracts.add(
                    new ValidatedContract(
                            contractId,
                            key,
                            required(contract.name(), 160, "契约名称"),
                            contract.providerRepositoryId(),
                            contract.consumerRepositoryId(),
                            provider,
                            consumer));
        }
        return new ValidatedProject(
                name, description == null ? "" : description, List.copyOf(members.values()), contracts);
    }

    private void persistTopology(
            UUID projectId, UUID actorId, ValidatedProject project, Instant now) {
        for (Member member : project.repositories()) {
            mapper.insertRepository(
                    projectId,
                    member.repositoryId(),
                    member.serviceName(),
                    member.serviceName(),
                    actorId,
                    now);
        }
        for (ValidatedContract contract : project.contracts()) {
            mapper.insertContract(
                    new EngineeringContractRow(
                            contract.id(),
                            projectId,
                            contract.contractKey(),
                            contract.name(),
                            contract.providerRepositoryId(),
                            contract.consumerRepositoryId(),
                            contract.provider().snapshotId(),
                            contract.consumer().snapshotId(),
                            contract.provider().filePath(),
                            contract.consumer().filePath(),
                            contract.provider().fingerprint(),
                            contract.consumer().fingerprint(),
                            now,
                            now),
                    contract.contractKey(),
                    actorId,
                    now);
        }
    }

    private void requireStableReferencedTopology(
            List<EngineeringProjectRepositoryRow> oldMembers,
            List<EngineeringContractRow> oldContracts,
            ValidatedProject updated) {
        Set<String> beforeMembers =
                oldMembers.stream()
                        .map(item -> item.repositoryId() + ":" + normalized(item.serviceName()))
                        .collect(Collectors.toSet());
        Set<String> afterMembers =
                updated.repositories().stream()
                        .map(item -> item.repositoryId() + ":" + item.serviceName())
                        .collect(Collectors.toSet());
        Set<UUID> beforeContracts =
                oldContracts.stream().map(EngineeringContractRow::id).collect(Collectors.toSet());
        Set<UUID> afterContracts =
                updated.contracts().stream().map(ValidatedContract::id).collect(Collectors.toSet());
        if (!afterMembers.containsAll(beforeMembers) || !afterContracts.containsAll(beforeContracts)) {
            throw new EngineeringProjectException(
                    "ENGINEERING_PROJECT_IN_USE",
                    "跨仓库知识正在引用当前拓扑；可以新增成员/契约或刷新证据，但不能删除或重命名既有仓库、服务和契约");
        }
    }

    private Evidence requireEvidence(UUID repositoryId, String requestedPath, String side) {
        String path = RepositoryGlobMatcher.normalizeRepositoryPath(requestedPath);
        Evidence evidence = currentEvidence(repositoryId, path);
        if (evidence.snapshotId() == null || evidence.fingerprint() == null) {
            throw new EngineeringProjectException(
                    "ENGINEERING_CONTRACT_EVIDENCE_NOT_FOUND",
                    side + "契约证据路径不在当前内容索引中: " + path);
        }
        return evidence;
    }

    private Evidence currentEvidence(UUID repositoryId, String filePath) {
        List<CurrentPathChunkRow> chunks = mapper.currentPathChunks(repositoryId, filePath);
        if (chunks.isEmpty()) {
            return new Evidence(null, filePath, null);
        }
        UUID snapshotId = chunks.get(0).snapshotId();
        if (chunks.stream()
                .anyMatch(
                        row ->
                                !Objects.equals(snapshotId, row.snapshotId())
                                        || row.contentHash() == null
                                        || row.contentHash().isBlank())) {
            return new Evidence(snapshotId, filePath, null);
        }
        String material =
                chunks.stream()
                        .map(row -> row.startLine() + ":" + row.contentHash())
                        .collect(Collectors.joining("\n"));
        return new Evidence(snapshotId, filePath, sha256(material));
    }

    private void requireManage(AuthenticatedAccount actor, List<UUID> repositoryIds) {
        repositoryIds.forEach(
                id ->
                        access.require(
                                actor, CodeRepositoryId.of(id), RepositoryPermission.MANAGE));
    }

    private EngineeringProjectRow required(UUID id) {
        EngineeringProjectRow row = mapper.findById(id);
        if (row == null) {
            throw new EngineeringProjectException("ENGINEERING_PROJECT_NOT_FOUND", "工程项目不存在");
        }
        return row;
    }

    private CodeRepository repository(UUID id) {
        return repositories
                .findById(CodeRepositoryId.of(id))
                .orElseThrow(
                        () ->
                                new EngineeringProjectException(
                                        "REPOSITORY_NOT_FOUND", "工程项目引用的仓库不存在"));
    }

    private static String required(String value, int maximum, String label) {
        String result = optional(value, maximum, label);
        if (result == null) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return result;
    }

    private static String optional(String value, int maximum, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String result = value.trim();
        if (result.length() > maximum
                || result.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw new IllegalArgumentException(label + "格式无效或超过 " + maximum + " 个字符");
        }
        return result;
    }

    private static String normalizedIdentifier(String value, String label) {
        String result = normalized(required(value, 120, label));
        if (!result.matches("[a-z0-9][a-z0-9._-]{0,119}")) {
            throw new IllegalArgumentException(label + "只能包含小写字母、数字、点、下划线和连字符");
        }
        return result;
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record ProjectInput(
            String name,
            String description,
            Long expectedVersion,
            List<MemberInput> repositories,
            List<ContractInput> contracts) {}

    public record MemberInput(UUID repositoryId, String serviceName) {}

    public record ContractInput(
            UUID id,
            String contractKey,
            String name,
            UUID providerRepositoryId,
            UUID consumerRepositoryId,
            String providerEvidencePath,
            String consumerEvidencePath) {}

    public record EngineeringProject(
            UUID id,
            String name,
            String description,
            long version,
            List<ProjectRepository> repositories,
            List<ContractView> contracts,
            Instant createdAt,
            Instant updatedAt) {}

    public record ProjectRepository(UUID repositoryId, String repositoryName, String serviceName) {}

    public record ContractView(
            UUID id,
            String contractKey,
            String name,
            UUID providerRepositoryId,
            UUID consumerRepositoryId,
            String providerEvidencePath,
            String consumerEvidencePath,
            boolean providerEvidenceCurrent,
            boolean consumerEvidenceCurrent) {
        @JsonProperty("current")
        public boolean current() {
            return providerEvidenceCurrent && consumerEvidenceCurrent;
        }
    }

    public record ReviewTopology(List<RepositoryBinding> repositories) {
        public ReviewTopology {
            repositories = repositories == null ? List.of() : List.copyOf(repositories);
        }
    }

    public record RepositoryBinding(
            UUID engineeringProjectId,
            UUID sourceRepositoryId,
            String targetServiceName,
            List<ContractBinding> contracts) {
        public RepositoryBinding {
            contracts = contracts == null ? List.of() : List.copyOf(contracts);
        }
    }

    public record ContractBinding(UUID contractId, String targetEvidencePath, boolean current) {}

    private record ValidatedProject(
            String name,
            String description,
            List<Member> repositories,
            List<ValidatedContract> contracts) {}

    private record Member(UUID repositoryId, String serviceName) {}

    private record ValidatedContract(
            UUID id,
            String contractKey,
            String name,
            UUID providerRepositoryId,
            UUID consumerRepositoryId,
            Evidence provider,
            Evidence consumer) {}

    private record Evidence(UUID snapshotId, String filePath, String fingerprint) {}
}
