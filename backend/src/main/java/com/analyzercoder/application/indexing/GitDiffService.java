package com.analyzercoder.application.indexing;

import com.analyzercoder.domain.repository.CodeRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 读取受控仓库的 Git 差异，并将命令输出转换为可供索引决策使用的文件变更集合。 */
@Component
public class GitDiffService {
    public Set<String> changedPaths(CodeRepository repository, String fromCommit) {
        if (fromCommit == null || fromCommit.isBlank() || repository.currentCommit() == null) {
            return Set.of();
        }
        try {
            Process process =
                    new ProcessBuilder(
                                    "git",
                                    "-C",
                                    repository.path().toString(),
                                    "diff",
                                    "--name-only",
                                    "-z",
                                    fromCommit,
                                    repository.currentCommit(),
                                    "--")
                            .redirectErrorStream(true)
                            .start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("无法计算增量差异，需执行全量索引");
            }
            Set<String> paths = new LinkedHashSet<>();
            Arrays.stream(new String(output, StandardCharsets.UTF_8).split("\\0"))
                    .map(value -> value.replace('\\', '/'))
                    .filter(value -> !value.isBlank())
                    .forEach(paths::add);
            return paths;
        } catch (IOException e) {
            throw new IllegalStateException("Git diff 不可用", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git diff 被中断", e);
        }
    }
}
