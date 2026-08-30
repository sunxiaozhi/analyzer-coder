package com.analyzercoder.application.review;

import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.change.RepositoryChangeException;
import com.analyzercoder.application.code.CodeSymbolExtractor;
import com.analyzercoder.application.code.CodeSymbolExtractor.SymbolDeclaration;
import com.analyzercoder.application.knowledge.RepositoryGlobMatcher;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.infrastructure.git.ProcessGitClient;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 将真实 Diff Hunk 映射到版本一致的 CodeGraph、源码声明、Chunk 或文件级对象。 */
@Service
public class ChangedSymbolResolver {
    static final int MAX_FILE_BYTES = 1024 * 1024;

    private final CodeSymbolExtractor extractor;
    private final CodeChunkStore chunks;
    private final ProcessGitClient git;
    private final List<CodeGraphSymbolLookup> graphLookups;

    @Autowired
    public ChangedSymbolResolver(
            CodeSymbolExtractor extractor,
            CodeChunkStore chunks,
            ProcessGitClient git,
            List<CodeGraphSymbolLookup> graphLookups) {
        this.extractor = extractor;
        this.chunks = chunks;
        this.git = git;
        this.graphLookups = graphLookups == null ? List.of() : List.copyOf(graphLookups);
    }

    public ChangedSymbolResolver(
            CodeSymbolExtractor extractor, CodeChunkStore chunks, ProcessGitClient git) {
        this(extractor, chunks, git, List.of());
    }

    public ResolutionResult resolve(CodeRepository repository, RepositoryChange change) {
        Objects.requireNonNull(repository, "repository must not be null");
        Objects.requireNonNull(change, "change must not be null");
        verifyWorktreeVersion(change, repository, "WORKTREE_CHANGED_BEFORE_SYMBOL_RESOLUTION");

        List<ChangedSymbol> symbols = new ArrayList<>();
        LinkedHashSet<ResolutionUnknown> unknowns = new LinkedHashSet<>();
        for (RepositoryChange.FileChange fileChange : change.changes()) {
            resolveFile(repository, change, fileChange, symbols, unknowns);
        }

        verifyWorktreeVersion(change, repository, "WORKTREE_CHANGED_DURING_SYMBOL_RESOLUTION");
        return new ResolutionResult(List.copyOf(symbols), List.copyOf(unknowns));
    }

    private void resolveFile(
            CodeRepository repository,
            RepositoryChange change,
            RepositoryChange.FileChange fileChange,
            List<ChangedSymbol> result,
            Set<ResolutionUnknown> unknowns) {
        LoadedSource oldSource =
                loadSource(
                        repository,
                        change,
                        fileChange,
                        Side.OLD,
                        fileChange.oldPath(),
                        change.baseCommit(),
                        unknowns);
        LoadedSource newSource =
                loadSource(
                        repository,
                        change,
                        fileChange,
                        Side.NEW,
                        fileChange.newPath(),
                        change.headCommit(),
                        unknowns);

        List<HunkContext> hunks = hunkContexts(fileChange, oldSource, newSource);
        for (int index = 0; index < hunks.size(); index++) {
            HunkContext hunk = hunks.get(index);
            List<SideSymbol> oldSymbols =
                    resolveSide(repository, change, fileChange, oldSource, hunk, index, unknowns);
            List<SideSymbol> newSymbols =
                    resolveSide(repository, change, fileChange, newSource, hunk, index, unknowns);
            List<SideSymbol> selected = selectSides(fileChange.type(), oldSymbols, newSymbols);
            mergeHunkSymbols(fileChange, hunk, index, selected, oldSymbols, result);
        }
    }

    private List<SideSymbol> resolveSide(
            CodeRepository repository,
            RepositoryChange change,
            RepositoryChange.FileChange fileChange,
            LoadedSource source,
            HunkContext hunk,
            int hunkIndex,
            Set<ResolutionUnknown> unknowns) {
        if (source.path() == null) {
            return List.of();
        }
        LineRange range = hunk.range(source.side());
        if (source.forcedFileLevel()) {
            return List.of(
                    fileLevel(repository, change, fileChange, source, range, source.reason()));
        }

        List<SideSymbol> graph =
                graphSymbols(repository, change, source, range, hunkIndex, unknowns);
        if (!graph.isEmpty()) {
            return graph;
        }

        List<SymbolDeclaration> declarations =
                hunk.synthetic()
                        ? source.extraction().symbols()
                        : leafDeclarations(source.extraction().symbols(), range);
        if (!declarations.isEmpty()) {
            return declarations.stream()
                    .map(declaration -> sourceSymbol(repository, change, source, declaration))
                    .toList();
        }

        List<SideSymbol> chunkSymbols =
                chunkSymbols(repository, change, source, range, hunkIndex, unknowns);
        if (!chunkSymbols.isEmpty()) {
            return chunkSymbols;
        }

        unknowns.add(
                new ResolutionUnknown(
                        "NO_SUPPORTED_DECLARATION",
                        source.path(),
                        hunkIndex,
                        "未找到与 Hunk 相交的可验证声明，已降级为文件级对象"));
        return List.of(
                fileLevel(repository, change, fileChange, source, range, "FILE_LEVEL_FALLBACK"));
    }

    private List<SideSymbol> graphSymbols(
            CodeRepository repository,
            RepositoryChange change,
            LoadedSource source,
            LineRange range,
            int hunkIndex,
            Set<ResolutionUnknown> unknowns) {
        UUID snapshotId = snapshotFor(repository, source);
        if (snapshotId == null || source.commitSha() == null || graphLookups.isEmpty()) {
            return List.of();
        }
        GraphLookupRequest request =
                new GraphLookupRequest(
                        repository.id().value(),
                        snapshotId,
                        source.commitSha(),
                        source.path(),
                        range.startLine(),
                        range.endLine(),
                        source.side());
        List<GraphSymbol> nodes = new ArrayList<>();
        for (CodeGraphSymbolLookup lookup : graphLookups) {
            try {
                List<GraphSymbol> candidates = lookup.lookup(request);
                if (candidates != null) {
                    if (candidates.stream().anyMatch(node -> graphVersionMismatch(node, request))) {
                        unknowns.add(
                                new ResolutionUnknown(
                                        "CODEGRAPH_VERSION_MISMATCH",
                                        source.path(),
                                        hunkIndex,
                                        "CodeGraph 返回了仓库、快照、提交或路径不一致的节点，已排除"));
                    }
                    candidates.stream()
                            .filter(node -> validGraphNode(node, request, range))
                            .forEach(nodes::add);
                }
            } catch (RuntimeException exception) {
                unknowns.add(
                        new ResolutionUnknown(
                                "CODEGRAPH_LOOKUP_UNAVAILABLE",
                                source.path(),
                                hunkIndex,
                                safeDetail(exception, "CodeGraph 符号查询不可用")));
            }
        }
        return leafGraphNodes(nodes, range).stream()
                .map(
                        node ->
                                new SideSymbol(
                                        source.side(),
                                        Resolution.CODEGRAPH,
                                        node.symbolId(),
                                        node.name(),
                                        node.kind(),
                                        source.path(),
                                        node.startLine(),
                                        node.endLine(),
                                        List.of(
                                                new Provenance(
                                                        ProvenanceType.CODEGRAPH_NODE,
                                                        repository.id().value(),
                                                        node.snapshotId(),
                                                        node.commitSha(),
                                                        change.worktreeDigest(),
                                                        source.path(),
                                                        node.startLine(),
                                                        node.endLine(),
                                                        source.side(),
                                                        "当前快照 CodeGraph 节点与 Hunk 相交"))))
                .toList();
    }

    private List<SideSymbol> chunkSymbols(
            CodeRepository repository,
            RepositoryChange change,
            LoadedSource source,
            LineRange range,
            int hunkIndex,
            Set<ResolutionUnknown> unknowns) {
        if (source.commitSha() == null) {
            return List.of();
        }
        try {
            return chunks.findByRepositoryPath(repository.id(), source.path()).stream()
                    .filter(chunk -> source.commitSha().equals(chunk.commitSha()))
                    .filter(chunk -> chunk.symbolName() != null && !chunk.symbolName().isBlank())
                    .filter(chunk -> chunk.symbolKind() != null && !chunk.symbolKind().isBlank())
                    .filter(chunk -> intersects(chunk.startLine(), chunk.endLine(), range))
                    .collect(
                            java.util.stream.Collectors.toMap(
                                    chunk -> chunk.symbolKind() + "\u0000" + chunk.symbolName(),
                                    chunk -> chunkSymbol(repository, change, source, chunk),
                                    (left, right) -> left,
                                    LinkedHashMap::new))
                    .values()
                    .stream()
                    .toList();
        } catch (RuntimeException exception) {
            unknowns.add(
                    new ResolutionUnknown(
                            "CHUNK_LOOKUP_UNAVAILABLE",
                            source.path(),
                            hunkIndex,
                            safeDetail(exception, "代码片段索引不可用")));
            return List.of();
        }
    }

    private SideSymbol sourceSymbol(
            CodeRepository repository,
            RepositoryChange change,
            LoadedSource source,
            SymbolDeclaration declaration) {
        return new SideSymbol(
                source.side(),
                Resolution.SOURCE_DECLARATION,
                symbolId(
                        declaration.language(),
                        source.path(),
                        declaration.kind(),
                        declaration.name()),
                declaration.name(),
                declaration.kind(),
                source.path(),
                declaration.startLine(),
                declaration.endLine(),
                List.of(
                        new Provenance(
                                ProvenanceType.SOURCE_TEXT,
                                repository.id().value(),
                                snapshotFor(repository, source),
                                source.commitSha(),
                                change.worktreeDigest(),
                                source.path(),
                                declaration.startLine(),
                                declaration.endLine(),
                                source.side(),
                                "源码中存在可验证的声明文本")));
    }

    private SideSymbol chunkSymbol(
            CodeRepository repository,
            RepositoryChange change,
            LoadedSource source,
            CodeChunk chunk) {
        return new SideSymbol(
                source.side(),
                Resolution.CHUNK_SYMBOL,
                chunk.symbolId() == null || chunk.symbolId().isBlank()
                        ? symbolId(
                                chunk.language(),
                                source.path(),
                                chunk.symbolKind(),
                                chunk.symbolName())
                        : chunk.symbolId(),
                chunk.symbolName(),
                chunk.symbolKind(),
                source.path(),
                value(chunk.startLine(), 1),
                value(chunk.endLine(), value(chunk.startLine(), 1)),
                List.of(
                        new Provenance(
                                ProvenanceType.CHUNK_INDEX,
                                repository.id().value(),
                                chunk.snapshotId().value(),
                                chunk.commitSha(),
                                change.worktreeDigest(),
                                source.path(),
                                chunk.startLine(),
                                chunk.endLine(),
                                source.side(),
                                "同提交代码片段符号与 Hunk 相交")));
    }

    private SideSymbol fileLevel(
            CodeRepository repository,
            RepositoryChange change,
            RepositoryChange.FileChange fileChange,
            LoadedSource source,
            LineRange range,
            String reason) {
        String name = fileName(source.path());
        return new SideSymbol(
                source.side(),
                Resolution.FILE_LEVEL,
                "file:" + source.path(),
                name,
                fileChange.binary() ? "BINARY_FILE" : "FILE",
                source.path(),
                range.startLine(),
                range.endLine(),
                List.of(
                        new Provenance(
                                ProvenanceType.FILE_CHANGE,
                                repository.id().value(),
                                snapshotFor(repository, source),
                                source.commitSha(),
                                change.worktreeDigest(),
                                source.path(),
                                range.startLine(),
                                range.endLine(),
                                source.side(),
                                reason)));
    }

    private LoadedSource loadSource(
            CodeRepository repository,
            RepositoryChange change,
            RepositoryChange.FileChange fileChange,
            Side side,
            String requestedPath,
            String commitSha,
            Set<ResolutionUnknown> unknowns) {
        if (requestedPath == null) {
            return LoadedSource.missing(side);
        }
        String path;
        try {
            path = RepositoryGlobMatcher.normalizeRepositoryPath(requestedPath);
        } catch (IllegalArgumentException exception) {
            throw new RepositoryChangeException(
                    "INVALID_GIT_PATH", exception.getMessage(), exception);
        }
        if (fileChange.binary()) {
            unknowns.add(
                    new ResolutionUnknown("BINARY_FILE", path, null, "二进制文件不执行源码声明解析，已降级为文件级对象"));
            return LoadedSource.forced(side, path, commitSha, "BINARY_FILE");
        }

        ContentResult content =
                side == Side.NEW
                                && change.source()
                                        == com.analyzercoder.application.change.GitChangeRequest
                                                .Source.WORKTREE
                        ? readWorktree(repository.path(), path)
                        : readCommit(repository.path(), commitSha, path);
        if (!content.available()) {
            unknowns.add(new ResolutionUnknown(content.reason(), path, null, content.detail()));
            return LoadedSource.forced(side, path, commitSha, content.reason());
        }
        if (extractor.generatedCode(path, content.content())) {
            unknowns.add(
                    new ResolutionUnknown(
                            "GENERATED_CODE_FILE_LEVEL", path, null, "动态生成代码按文件级对象处理"));
            return LoadedSource.forced(side, path, commitSha, "GENERATED_CODE_FILE_LEVEL");
        }
        CodeSymbolExtractor.Extraction extraction =
                extractor.extract(content.content(), path, null);
        if (extraction.truncated()) {
            unknowns.add(
                    new ResolutionUnknown(
                            extraction.limitationCode(),
                            path,
                            null,
                            "单文件声明超过 500 个，仅保留前 500 个真实声明"));
        }
        return LoadedSource.available(
                side, path, commitSha, content.content(), extraction, lineCount(content.content()));
    }

    private ContentResult readWorktree(Path repositoryRoot, String repositoryPath) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        Path file = root.resolve(Path.of(repositoryPath)).normalize();
        if (!file.startsWith(root)) {
            throw new RepositoryChangeException("INVALID_GIT_PATH", "文件路径超出仓库范围");
        }
        try {
            if (Files.isSymbolicLink(file)) {
                return ContentResult.unavailable("SYMLINK_FILE_LEVEL", "符号链接不读取目标内容");
            }
            Path realRoot = root.toRealPath();
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                return ContentResult.unavailable("SYMLINK_FILE_LEVEL", "文件经符号链接解析后超出仓库范围");
            }
            if (!Files.isRegularFile(file)) {
                return ContentResult.unavailable("WORKTREE_FILE_UNAVAILABLE", "工作区文件不存在或不可读取");
            }
            if (Files.size(file) > MAX_FILE_BYTES) {
                return ContentResult.unavailable(
                        "FILE_SIZE_LIMIT_EXCEEDED", "单文件超过 1 MiB，已降级为文件级对象");
            }
            return decode(Files.readAllBytes(file));
        } catch (IOException exception) {
            return ContentResult.unavailable(
                    "WORKTREE_FILE_UNAVAILABLE", safeDetail(exception, "无法读取工作区文件"));
        }
    }

    private ContentResult readCommit(Path repositoryRoot, String commitSha, String repositoryPath) {
        if (commitSha == null || !commitSha.matches("(?i)[0-9a-f]{40,64}")) {
            return ContentResult.unavailable("BASE_CONTENT_UNAVAILABLE", "缺少可验证的提交对象 ID");
        }
        String object = commitSha + ":" + repositoryPath;
        try {
            ProcessGitClient.CommandResult result =
                    git.run(
                            repositoryRoot,
                            MAX_FILE_BYTES,
                            List.of(
                                    "show",
                                    "--no-color",
                                    "--no-ext-diff",
                                    "--no-textconv",
                                    "--format=",
                                    "--end-of-options",
                                    object));
            if (result.exitCode() != 0) {
                return ContentResult.unavailable("BASE_CONTENT_UNAVAILABLE", "无法从提交读取文件内容");
            }
            if (result.stdoutTruncated()) {
                return ContentResult.unavailable(
                        "FILE_SIZE_LIMIT_EXCEEDED", "提交中的文件超过 1 MiB，已降级为文件级对象");
            }
            return decode(result.stdout());
        } catch (ProcessGitClient.GitClientException exception) {
            return ContentResult.unavailable(exception.code(), safeDetail(exception, "Git 文件读取失败"));
        }
    }

    private static ContentResult decode(byte[] bytes) {
        int binaryProbe = Math.min(bytes.length, 8_000);
        for (int index = 0; index < binaryProbe; index++) {
            if (bytes[index] == 0) {
                return ContentResult.unavailable("BINARY_FILE", "文件内容包含二进制标记");
            }
        }
        try {
            String content =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            return ContentResult.available(content);
        } catch (CharacterCodingException exception) {
            return ContentResult.unavailable(
                    "SOURCE_ENCODING_UNSUPPORTED", "源码不是有效 UTF-8，已降级为文件级对象");
        }
    }

    private void verifyWorktreeVersion(
            RepositoryChange change, CodeRepository repository, String errorCode) {
        if (change.source()
                != com.analyzercoder.application.change.GitChangeRequest.Source.WORKTREE) {
            return;
        }
        if (change.worktreeDigest() == null || change.worktreeDigest().isBlank()) {
            throw new RepositoryChangeException("CHANGE_VERSION_MISSING", "工作区变更缺少内容摘要");
        }
        String current;
        try {
            current = git.worktreeDigest(repository.path());
        } catch (ProcessGitClient.GitClientException exception) {
            throw new RepositoryChangeException(
                    exception.code(), exception.getMessage(), exception);
        }
        if (!change.worktreeDigest().equals(current)) {
            throw new RepositoryChangeException(errorCode, "符号识别期间工作区版本不一致");
        }
    }

    private static List<HunkContext> hunkContexts(
            RepositoryChange.FileChange fileChange,
            LoadedSource oldSource,
            LoadedSource newSource) {
        if (!fileChange.hunks().isEmpty()) {
            return fileChange.hunks().stream().map(hunk -> new HunkContext(hunk, false)).toList();
        }
        int oldCount = oldSource.lineCount();
        int newCount = newSource.lineCount();
        return List.of(
                new HunkContext(
                        new RepositoryChange.Hunk(
                                oldCount == 0 ? 0 : 1, oldCount, newCount == 0 ? 0 : 1, newCount),
                        true));
    }

    private static List<SideSymbol> selectSides(
            RepositoryChange.ChangeType type,
            List<SideSymbol> oldSymbols,
            List<SideSymbol> newSymbols) {
        return switch (type) {
            case ADDED, COPIED -> newSymbols;
            case DELETED -> oldSymbols;
            case MODIFIED, RENAMED -> append(newSymbols, oldSymbols);
        };
    }

    private static void mergeHunkSymbols(
            RepositoryChange.FileChange fileChange,
            HunkContext hunk,
            int hunkIndex,
            List<SideSymbol> selected,
            List<SideSymbol> oldSymbols,
            List<ChangedSymbol> target) {
        Map<String, SideSymbol> merged = new LinkedHashMap<>();
        for (SideSymbol symbol : selected) {
            String key = symbol.path() + "\u0000" + symbol.kind() + "\u0000" + symbol.name();
            merged.merge(key, symbol, ChangedSymbolResolver::mergeSideSymbol);
        }
        if (fileChange.type() == RepositoryChange.ChangeType.COPIED) {
            for (Map.Entry<String, SideSymbol> entry : new ArrayList<>(merged.entrySet())) {
                SideSymbol old =
                        oldSymbols.stream()
                                .filter(
                                        candidate ->
                                                candidate.name().equals(entry.getValue().name()))
                                .filter(
                                        candidate ->
                                                candidate.kind().equals(entry.getValue().kind()))
                                .findFirst()
                                .orElse(null);
                if (old != null) {
                    merged.put(entry.getKey(), mergeSideSymbol(entry.getValue(), old));
                }
            }
        }
        for (SideSymbol symbol : merged.values()) {
            target.add(
                    new ChangedSymbol(
                            symbol.symbolId(),
                            symbol.name(),
                            symbol.kind(),
                            symbol.path(),
                            symbol.declarationStartLine(),
                            symbol.declarationEndLine(),
                            fileChange.type(),
                            fileChange.type() == RepositoryChange.ChangeType.ADDED
                                    ? null
                                    : hunk.hunk().oldStart(),
                            fileChange.type() == RepositoryChange.ChangeType.DELETED
                                    ? null
                                    : hunk.hunk().newStart(),
                            hunkIndex,
                            hunk.synthetic(),
                            symbol.resolution(),
                            symbol.provenance()));
        }
    }

    private static SideSymbol mergeSideSymbol(SideSymbol left, SideSymbol right) {
        List<Provenance> provenance = append(left.provenance(), right.provenance());
        SideSymbol preferred =
                left.resolution().priority() <= right.resolution().priority() ? left : right;
        return new SideSymbol(
                preferred.side(),
                preferred.resolution(),
                preferred.symbolId(),
                preferred.name(),
                preferred.kind(),
                preferred.path(),
                preferred.declarationStartLine(),
                preferred.declarationEndLine(),
                provenance);
    }

    private static List<SymbolDeclaration> leafDeclarations(
            List<SymbolDeclaration> symbols, LineRange range) {
        List<SymbolDeclaration> intersecting =
                symbols.stream()
                        .filter(symbol -> intersects(symbol.startLine(), symbol.endLine(), range))
                        .toList();
        return intersecting.stream()
                .filter(
                        candidate ->
                                intersecting.stream()
                                        .noneMatch(
                                                other ->
                                                        other != candidate
                                                                && other.startLine()
                                                                        >= candidate.startLine()
                                                                && other.endLine()
                                                                        <= candidate.endLine()
                                                                && other.lineSpan()
                                                                        < candidate.lineSpan()))
                .sorted(Comparator.comparingInt(SymbolDeclaration::startLine))
                .toList();
    }

    private static List<GraphSymbol> leafGraphNodes(List<GraphSymbol> nodes, LineRange range) {
        List<GraphSymbol> intersecting =
                nodes.stream()
                        .filter(node -> intersects(node.startLine(), node.endLine(), range))
                        .toList();
        return intersecting.stream()
                .filter(
                        candidate ->
                                intersecting.stream()
                                        .noneMatch(
                                                other ->
                                                        other != candidate
                                                                && other.startLine()
                                                                        >= candidate.startLine()
                                                                && other.endLine()
                                                                        <= candidate.endLine()
                                                                && span(
                                                                                other.startLine(),
                                                                                other.endLine())
                                                                        < span(
                                                                                candidate
                                                                                        .startLine(),
                                                                                candidate
                                                                                        .endLine())))
                .sorted(Comparator.comparingInt(GraphSymbol::startLine))
                .toList();
    }

    private static boolean validGraphNode(
            GraphSymbol node, GraphLookupRequest request, LineRange range) {
        return node != null
                && request.repositoryId().equals(node.repositoryId())
                && request.snapshotId().equals(node.snapshotId())
                && request.commitSha().equals(node.commitSha())
                && request.filePath().equals(node.filePath())
                && node.symbolId() != null
                && !node.symbolId().isBlank()
                && node.name() != null
                && !node.name().isBlank()
                && node.kind() != null
                && !node.kind().isBlank()
                && intersects(node.startLine(), node.endLine(), range);
    }

    private static boolean graphVersionMismatch(GraphSymbol node, GraphLookupRequest request) {
        return node != null
                && (!request.repositoryId().equals(node.repositoryId())
                        || !request.snapshotId().equals(node.snapshotId())
                        || !request.commitSha().equals(node.commitSha())
                        || !request.filePath().equals(node.filePath()));
    }

    private static boolean intersects(Integer start, Integer end, LineRange range) {
        if (start == null || end == null) {
            return false;
        }
        return start <= range.endLine() && end >= range.startLine();
    }

    private static int span(int start, int end) {
        return Math.max(0, end - start);
    }

    private static UUID snapshotFor(CodeRepository repository, LoadedSource source) {
        if (repository.currentSnapshotId() == null
                || repository.currentCommit() == null
                || source.commitSha() == null
                || !repository.currentCommit().equals(source.commitSha())) {
            return null;
        }
        return repository.currentSnapshotId().value();
    }

    private static String symbolId(String language, String path, String kind, String name) {
        String normalizedLanguage =
                language == null || language.isBlank()
                        ? "unknown"
                        : language.toLowerCase(Locale.ROOT);
        return normalizedLanguage + ":" + path + ":" + kind + ":" + name;
    }

    private static String fileName(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private static int lineCount(String content) {
        return content.isEmpty() ? 0 : Math.toIntExact(content.lines().count());
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static <T> List<T> append(List<T> first, List<T> second) {
        List<T> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private static String safeDetail(Exception exception, String fallback) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message.substring(0, Math.min(message.length(), 300));
    }

    public enum Resolution {
        CODEGRAPH(0),
        SOURCE_DECLARATION(1),
        CHUNK_SYMBOL(2),
        FILE_LEVEL(3);

        private final int priority;

        Resolution(int priority) {
            this.priority = priority;
        }

        int priority() {
            return priority;
        }
    }

    public enum Side {
        OLD,
        NEW
    }

    public enum ProvenanceType {
        CODEGRAPH_NODE,
        SOURCE_TEXT,
        CHUNK_INDEX,
        FILE_CHANGE
    }

    public record ChangedSymbol(
            String symbolId,
            String name,
            String kind,
            String filePath,
            int declarationStartLine,
            int declarationEndLine,
            RepositoryChange.ChangeType changeType,
            Integer oldStartLine,
            Integer newStartLine,
            int hunkIndex,
            boolean syntheticHunk,
            Resolution resolution,
            List<Provenance> provenance) {
        public ChangedSymbol {
            provenance = provenance == null ? List.of() : List.copyOf(provenance);
        }
    }

    public record Provenance(
            ProvenanceType sourceType,
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String worktreeDigest,
            String filePath,
            Integer startLine,
            Integer endLine,
            Side side,
            String detail) {}

    public record ResolutionResult(List<ChangedSymbol> symbols, List<ResolutionUnknown> unknowns) {
        public ResolutionResult {
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
            unknowns = unknowns == null ? List.of() : List.copyOf(unknowns);
        }
    }

    public record ResolutionUnknown(
            String code, String filePath, Integer hunkIndex, String detail) {}

    public interface CodeGraphSymbolLookup {
        List<GraphSymbol> lookup(GraphLookupRequest request);
    }

    public record GraphLookupRequest(
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String filePath,
            int startLine,
            int endLine,
            Side side) {}

    public record GraphSymbol(
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String filePath,
            String symbolId,
            String name,
            String kind,
            int startLine,
            int endLine) {}

    private record SideSymbol(
            Side side,
            Resolution resolution,
            String symbolId,
            String name,
            String kind,
            String path,
            int declarationStartLine,
            int declarationEndLine,
            List<Provenance> provenance) {}

    private record LoadedSource(
            Side side,
            String path,
            String commitSha,
            String content,
            CodeSymbolExtractor.Extraction extraction,
            int lineCount,
            boolean forcedFileLevel,
            String reason) {
        static LoadedSource missing(Side side) {
            return new LoadedSource(side, null, null, "", emptyExtraction(), 0, false, null);
        }

        static LoadedSource forced(Side side, String path, String commitSha, String reason) {
            return new LoadedSource(side, path, commitSha, "", emptyExtraction(), 1, true, reason);
        }

        static LoadedSource available(
                Side side,
                String path,
                String commitSha,
                String content,
                CodeSymbolExtractor.Extraction extraction,
                int lineCount) {
            return new LoadedSource(
                    side, path, commitSha, content, extraction, lineCount, false, null);
        }

        private static CodeSymbolExtractor.Extraction emptyExtraction() {
            return new CodeSymbolExtractor.Extraction("", List.of(), false, null);
        }
    }

    private record HunkContext(RepositoryChange.Hunk hunk, boolean synthetic) {
        LineRange range(Side side) {
            int start = side == Side.OLD ? hunk.oldStart() : hunk.newStart();
            int count = side == Side.OLD ? hunk.oldCount() : hunk.newCount();
            int safeStart = Math.max(1, start);
            return new LineRange(safeStart, safeStart + Math.max(1, count) - 1);
        }
    }

    private record LineRange(int startLine, int endLine) {}

    private record ContentResult(boolean available, String content, String reason, String detail) {
        static ContentResult available(String content) {
            return new ContentResult(true, content, null, null);
        }

        static ContentResult unavailable(String reason, String detail) {
            return new ContentResult(false, "", reason, detail);
        }
    }
}
