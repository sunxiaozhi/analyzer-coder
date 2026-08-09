package com.analyzercoder.application.repository;

import com.analyzercoder.application.indexing.IndexJobUseCase;
import com.analyzercoder.application.indexing.StartIndexCommand;
import com.analyzercoder.application.indexing.VectorIndexQueryService;
import com.analyzercoder.application.intelligence.CodeGraphTaskService;
import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import com.analyzercoder.security.AuthenticatedAccount;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RepositoryPreparationService {
    private final RegisterRepositoryUseCase repositories;
    private final RepositoryRemoteSyncService remoteSync;
    private final RepositoryCodeBrowserService browser;
    private final VectorIndexQueryService vectors;
    private final CodeGraphArtifactMapper graphArtifacts;
    private final IndexJobUseCase indexJobs;
    private final IndexJobStore indexJobStore;
    private final CodeGraphTaskService codeGraphTasks;

    public RepositoryPreparationService(
        RegisterRepositoryUseCase repositories,
        RepositoryRemoteSyncService remoteSync,
        RepositoryCodeBrowserService browser,
        VectorIndexQueryService vectors,
        CodeGraphArtifactMapper graphArtifacts,
        IndexJobUseCase indexJobs,
        IndexJobStore indexJobStore,
        CodeGraphTaskService codeGraphTasks
    ) {
        this.repositories = repositories;
        this.remoteSync = remoteSync;
        this.browser = browser;
        this.vectors = vectors;
        this.graphArtifacts = graphArtifacts;
        this.indexJobs = indexJobs;
        this.indexJobStore = indexJobStore;
        this.codeGraphTasks = codeGraphTasks;
    }

    public PreparationView view(CodeRepositoryId repositoryId) {
        CodeRepository repository = repositories.get(repositoryId);
        return view(repository, latest(repositoryId));
    }

    public PreparationView prepare(AuthenticatedAccount actor, CodeRepositoryId repositoryId) {
        CodeRepository repository = repositories.get(repositoryId);
        IndexJob active = active(repositoryId);
        if (active != null) return view(repository, active);

        boolean changed = false;
        if (repository.sourceType() == RepositorySourceType.REMOTE_GIT
            || repository.sourceType() == RepositorySourceType.GITLAB) {
            changed = remoteSync.sync(actor, repositoryId).changed();
        } else {
            changed = repositories.rescan(repositoryId).changed();
        }

        repository = repositories.get(repositoryId);
        active = active(repositoryId);
        if (active != null) return view(repository, active);

        if (changed) {
            IndexJob started = indexJobs.start(new StartIndexCommand(repositoryId, IndexJobType.INCREMENTAL));
            return view(repository, started);
        }

        VectorIndexQueryService.Summary summary = vectors.summary(repositoryId.value());
        if (summary.totalChunks() == 0) {
            IndexJob started = indexJobs.start(new StartIndexCommand(repositoryId, IndexJobType.FULL));
            return view(repository, started);
        }

        CodeGraphArtifactRow graph = currentGraph(repository);
        if (graph == null) {
            IndexJob graphJob = codeGraphTasks.start(repositoryId);
            return view(repository, graphJob);
        }
        return view(repository, latest(repositoryId));
    }

    private PreparationView view(CodeRepository repository, IndexJob latestJob) {
        VectorIndexQueryService.Summary summary = vectors.summary(repository.id().value());
        CodeGraphArtifactRow graph = currentGraph(repository);
        RepositoryCodeBrowserService.SnapshotFiles snapshot = browser.list(repository.id());
        ProjectProfile profile = profile(snapshot.files(), summary, graph);
        boolean jobActive = latestJob != null && isActive(latestJob.status());
        boolean jobFailed = latestJob != null && latestJob.status() == IndexJobStatus.FAILED;
        boolean snapshotReady = repository.currentSnapshotId() != null;
        boolean contentReady = snapshotReady && summary.totalChunks() > 0;
        boolean vectorsReady = contentReady && summary.missingChunks() == 0;
        boolean graphReady = graph != null;

        List<PreparationStage> stages = List.of(
            new PreparationStage("snapshot", "代码快照", snapshotReady ? "READY" : "PENDING",
                snapshotReady ? snapshot.files().size() + " 个文件已发布" : "等待发布代码快照"),
            new PreparationStage("content", "内容索引", stage(contentReady, jobActive, jobFailed, latestJob, false),
                contentReady ? summary.totalChunks() + " 个代码片段" : jobDetail(latestJob, "等待生成代码片段")),
            new PreparationStage("vectors", "向量索引", vectorsReady ? "READY" : contentReady ? "DEGRADED" : jobActive ? "RUNNING" : "PENDING",
                vectorsReady ? summary.vectorizedChunks() + " 个片段可语义检索"
                    : contentReady ? summary.missingChunks() + " 个片段缺少向量，关键词检索仍可用" : "等待内容索引"),
            new PreparationStage("graph", "调用图谱", graphReady ? "READY" : graphJobRunning(latestJob) ? "RUNNING"
                : graphJobFailed(latestJob) ? "FAILED" : "PENDING",
                graphReady ? graph.nodeCount() + " 个节点 · " + graph.edgeCount() + " 条边"
                    : graphJobFailed(latestJob) ? jobDetail(latestJob, "CodeGraph 构建失败") : "等待构建 CodeGraph")
        );

        int progress = (snapshotReady ? 25 : 0) + (contentReady ? 25 : 0)
            + (vectorsReady ? 25 : 0) + (graphReady ? 25 : 0);
        String state = jobActive ? "PROCESSING" : graphReady && contentReady ? (vectorsReady ? "READY" : "DEGRADED")
            : jobFailed ? "ACTION_REQUIRED" : "NOT_READY";
        String message = switch (state) {
            case "READY" -> "项目已经可以开始检索、问答和图谱分析";
            case "DEGRADED" -> "项目主体已准备完成，向量能力处于降级状态";
            case "PROCESSING" -> "正在准备项目，完成当前阶段后会自动继续";
            case "ACTION_REQUIRED" -> jobDetail(latestJob, "项目准备失败，请重试");
            default -> "项目尚未完成准备";
        };
        return new PreparationView(repository.id().value(), state, progress, message, stages, profile,
            latestJob == null ? null : latestJob.id().value(), latestJob == null ? null : latestJob.type().name(),
            latestJob == null ? null : latestJob.status().name());
    }

    private CodeGraphArtifactRow currentGraph(CodeRepository repository) {
        if (repository.currentSnapshotId() == null) return null;
        return graphArtifacts.findPublished(repository.id().value(), repository.currentSnapshotId().value());
    }

    private IndexJob active(CodeRepositoryId repositoryId) {
        return indexJobStore.findByRepositoryId(repositoryId).stream()
            .filter(job -> isActive(job.status())).findFirst().orElse(null);
    }

    private IndexJob latest(CodeRepositoryId repositoryId) {
        return indexJobStore.findLatestByRepositoryId(repositoryId).orElse(null);
    }

    static ProjectProfile profile(
        List<RepositoryCodeBrowserService.FileEntry> files,
        VectorIndexQueryService.Summary summary,
        CodeGraphArtifactRow graph
    ) {
        Map<String, Long> languages = new LinkedHashMap<>();
        Map<String, Long> modules = new LinkedHashMap<>();
        List<String> entryPoints = new ArrayList<>();
        long totalBytes = 0;
        for (RepositoryCodeBrowserService.FileEntry file : files) {
            totalBytes += file.sizeBytes();
            String language = normalizedLabel(file.language(), "其他");
            languages.merge(language, 1L, Long::sum);
            String path = file.path().replace('\\', '/');
            int slash = path.indexOf('/');
            if (slash > 0) modules.merge(path.substring(0, slash), 1L, Long::sum);
            if (isEntryPoint(file.name())) entryPoints.add(path);
        }
        List<ProfileCount> languageCounts = topCounts(languages, 6);
        List<ProfileCount> moduleCounts = topCounts(modules, 8);
        entryPoints.sort(Comparator.comparingInt(RepositoryPreparationService::pathDepth).thenComparing(String::compareToIgnoreCase));
        if (entryPoints.size() > 8) entryPoints = new ArrayList<>(entryPoints.subList(0, 8));
        return new ProjectProfile(files.size(), totalBytes, summary.totalChunks(), summary.vectorizedChunks(),
            summary.missingChunks(), summary.knowledgeCards(), graph == null ? 0 : graph.nodeCount(),
            graph == null ? 0 : graph.edgeCount(), languageCounts, moduleCounts, List.copyOf(entryPoints));
    }

    private static List<ProfileCount> topCounts(Map<String, Long> values, int limit) {
        return values.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(limit).map(entry -> new ProfileCount(entry.getKey(), entry.getValue())).toList();
    }

    private static boolean isEntryPoint(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        int dot = name.indexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        return List.of("main", "app", "application", "index", "server", "cli", "bootstrap").contains(stem)
            || List.of("pom.xml", "package.json", "dockerfile", "compose.yaml", "compose.yml", "makefile").contains(name);
    }

    private static int pathDepth(String path) {
        return (int) path.chars().filter(value -> value == '/').count();
    }

    private static String normalizedLabel(String value, String fallback) {
        return value == null || value.isBlank() || "text".equalsIgnoreCase(value) ? fallback : value;
    }

    private static boolean isActive(IndexJobStatus status) {
        return status == IndexJobStatus.QUEUED || status == IndexJobStatus.RUNNING
            || status == IndexJobStatus.CANCEL_REQUESTED;
    }

    private static boolean graphJobRunning(IndexJob job) {
        return job != null && job.type() == IndexJobType.CODEGRAPH && isActive(job.status());
    }

    private static boolean graphJobFailed(IndexJob job) {
        return job != null && job.type() == IndexJobType.CODEGRAPH && job.status() == IndexJobStatus.FAILED;
    }

    private static String stage(boolean ready, boolean active, boolean failed, IndexJob job, boolean graph) {
        if (ready) return "READY";
        if (active && job != null && (graph == (job.type() == IndexJobType.CODEGRAPH))) return "RUNNING";
        return failed ? "FAILED" : "PENDING";
    }

    private static String jobDetail(IndexJob job, String fallback) {
        if (job == null) return fallback;
        if (job.errorMessage() != null && !job.errorMessage().isBlank()) return job.errorMessage();
        return job.currentStep() == null || job.currentStep().isBlank() ? fallback : job.currentStep();
    }

    public record PreparationView(
        UUID repositoryId,
        String state,
        int progress,
        String message,
        List<PreparationStage> stages,
        ProjectProfile profile,
        UUID activeJobId,
        String activeJobType,
        String activeJobStatus
    ) {}

    public record PreparationStage(String key, String label, String state, String detail) {}

    public record ProjectProfile(
        long fileCount,
        long totalBytes,
        long chunkCount,
        long vectorizedChunks,
        long missingChunks,
        long knowledgeCards,
        int graphNodes,
        int graphEdges,
        List<ProfileCount> languages,
        List<ProfileCount> modules,
        List<String> entryPoints
    ) {}

    public record ProfileCount(String name, long count) {}
}
