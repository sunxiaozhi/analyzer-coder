package com.analyzercoder.application.architecture;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.CodeChunkMapper;
import com.analyzercoder.infrastructure.persistence.model.ModuleSymbolRow;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 查询当前快照中属于指定架构模块的真实代码符号。 */
@Service
public class ProjectArchitectureSymbolService {
    private static final int DEFAULT_LIMIT = 80;
    private static final int MAX_LIMIT = 200;

    private final ProjectArchitectureMapService architecture;
    private final CodeChunkMapper chunks;

    public ProjectArchitectureSymbolService(
            ProjectArchitectureMapService architecture, CodeChunkMapper chunks) {
        this.architecture = architecture;
        this.chunks = chunks;
    }

    public ModuleSymbols symbols(CodeRepositoryId repositoryId, String module, Integer limit) {
        String normalizedModule = normalizeModule(module);
        ProjectArchitectureMapService.ArchitectureMap map = architecture.map(repositoryId);
        ProjectArchitectureMapService.ArchitectureNode node =
                map.nodes().stream()
                        .filter(candidate -> "MODULE".equals(candidate.kind()))
                        .filter(candidate -> candidate.id().equals(normalizedModule))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("当前快照不存在该架构模块"));

        int requested = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        boolean rootOnly = "(root)".equals(node.id());
        String modulePrefix = node.path();
        String layerSegment = null;
        if (ProjectArchitectureMapService.isLayerModule(node.id())) {
            int separator = node.id().lastIndexOf('/');
            modulePrefix = node.id().substring(0, separator);
            layerSegment = node.id().substring(separator + 1);
        }
        List<ModuleSymbolRow> rows =
                chunks.findModuleSymbols(
                        repositoryId.value(),
                        UUID.fromString(map.snapshotId()),
                        modulePrefix,
                        layerSegment,
                        rootOnly,
                        requested + 1);
        boolean truncated = rows.size() > requested;
        List<ModuleSymbol> symbols =
                rows.stream()
                        .limit(requested)
                        .map(
                                row ->
                                        new ModuleSymbol(
                                                row.symbolName(),
                                                row.symbolKind(),
                                                row.filePath(),
                                                row.startLine(),
                                                row.endLine(),
                                                row.language()))
                        .toList();
        return new ModuleSymbols(
                repositoryId.value().toString(),
                map.snapshotId(),
                normalizedModule,
                symbols,
                truncated);
    }

    private static String normalizeModule(String module) {
        if (module == null || module.isBlank()) {
            throw new IllegalArgumentException("module 不能为空");
        }
        String normalized = module.trim().replace('\\', '/');
        if (normalized.startsWith("/")
                || normalized.endsWith("/")
                || normalized.contains("../")
                || normalized.contains("/..")) {
            throw new IllegalArgumentException("module 不是有效的架构模块标识");
        }
        return normalized;
    }

    public record ModuleSymbols(
            String repositoryId,
            String snapshotId,
            String module,
            List<ModuleSymbol> symbols,
            boolean truncated) {}

    public record ModuleSymbol(
            String symbolName,
            String symbolKind,
            String filePath,
            Integer startLine,
            Integer endLine,
            String language) {}
}
