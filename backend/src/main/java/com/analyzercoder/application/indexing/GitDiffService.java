package com.analyzercoder.application.indexing;

import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.change.RepositoryChangeService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.infrastructure.git.ProcessGitClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 读取 Git name-status 差异，保留删除和重命名前后的路径语义。 */
@Component
public class GitDiffService {
    private final RepositoryChangeService repositoryChanges;

    @Autowired
    public GitDiffService(RepositoryChangeService repositoryChanges) {
        this.repositoryChanges = repositoryChanges;
    }

    public GitDiffService() {
        this(new RepositoryChangeService(new ProcessGitClient()));
    }

    public DiffResult diff(CodeRepository repository, String fromCommit) {
        if (fromCommit == null || fromCommit.isBlank() || repository.currentCommit() == null) {
            throw new IllegalArgumentException("增量索引缺少有效提交基线");
        }
        RepositoryChange result =
                repositoryChanges.analyze(
                        GitChangeRequest.commitRange(
                                repository.path(), fromCommit, repository.currentCommit()));
        if (result.partial()) {
            throw new IllegalStateException("Git diff 结果不完整，增量索引已拒绝使用");
        }
        List<FileChange> changes =
                result.changes().stream()
                        .map(
                                change ->
                                        new FileChange(
                                                switch (change.type()) {
                                                    case ADDED -> ChangeType.ADDED;
                                                    case MODIFIED -> ChangeType.MODIFIED;
                                                    case DELETED -> ChangeType.DELETED;
                                                    case RENAMED -> ChangeType.RENAMED;
                                                    case COPIED -> ChangeType.COPIED;
                                                },
                                                change.oldPath(),
                                                change.newPath()))
                        .toList();
        return new DiffResult(changes);
    }

    static DiffResult parseNameStatus(byte[] output) {
        String[] tokens = new String(output, StandardCharsets.UTF_8).split("\u0000", -1);
        List<FileChange> changes = new ArrayList<>();
        for (int index = 0; index < tokens.length; ) {
            String status = tokens[index++];
            if (status.isBlank()) continue;
            char code = status.charAt(0);
            if (index >= tokens.length) throw new IllegalStateException("Git diff 输出缺少文件路径");
            String first = normalize(tokens[index++]);
            if (code == 'R' || code == 'C') {
                if (index >= tokens.length) throw new IllegalStateException("Git diff 重命名输出不完整");
                String second = normalize(tokens[index++]);
                changes.add(
                        new FileChange(
                                code == 'R' ? ChangeType.RENAMED : ChangeType.COPIED,
                                first,
                                second));
            } else {
                ChangeType type =
                        switch (code) {
                            case 'A' -> ChangeType.ADDED;
                            case 'D' -> ChangeType.DELETED;
                            case 'M', 'T' -> ChangeType.MODIFIED;
                            default -> ChangeType.MODIFIED;
                        };
                changes.add(
                        new FileChange(
                                type,
                                type == ChangeType.ADDED ? null : first,
                                type == ChangeType.DELETED ? null : first));
            }
        }
        return new DiffResult(List.copyOf(changes));
    }

    private static String normalize(String value) {
        String path = value.replace('\\', '/');
        if (path.isBlank() || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalStateException("Git diff 返回非法路径");
        }
        for (String part : path.split("/")) {
            if ("..".equals(part)) throw new IllegalStateException("Git diff 路径越界");
        }
        return path;
    }

    public enum ChangeType {
        ADDED,
        MODIFIED,
        DELETED,
        RENAMED,
        COPIED
    }

    public record FileChange(ChangeType type, String oldPath, String newPath) {}

    public record DiffResult(List<FileChange> changes) {
        public Set<String> affectedPaths() {
            Set<String> paths = new LinkedHashSet<>();
            for (FileChange change : changes) {
                if (change.type() != ChangeType.COPIED && change.oldPath() != null) {
                    paths.add(change.oldPath());
                }
                if (change.newPath() != null) paths.add(change.newPath());
            }
            return Set.copyOf(paths);
        }

        public Set<String> indexPaths() {
            Set<String> paths = new LinkedHashSet<>();
            for (FileChange change : changes) {
                if (change.newPath() != null) paths.add(change.newPath());
            }
            return Set.copyOf(paths);
        }

        public int changeCount() {
            return changes.size();
        }
    }
}
