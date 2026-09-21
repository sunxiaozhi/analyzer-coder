package com.analyzercoder.application.repository;

import com.analyzercoder.infrastructure.repository.GitRuntimePolicy;
import com.analyzercoder.infrastructure.repository.RemoteRepositoryTargetPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 在最小暴露范围内向 Git 子进程提供临时凭据，并在执行结束后清理敏感环境。 */
@Component
public class GitCredentialExecutor {
    private static final Logger log = LoggerFactory.getLogger(GitCredentialExecutor.class);
    private static final int MAX_DISCOVERED_BRANCHES = 2_000;
    private final RemoteRepositoryTargetPolicy remoteTargets;

    @Autowired
    public GitCredentialExecutor(RemoteRepositoryTargetPolicy remoteTargets) {
        this.remoteTargets = remoteTargets;
    }

    GitCredentialExecutor() {
        this(new RemoteRepositoryTargetPolicy(""));
    }

    public record RemoteBranch(String name, String commitSha) {}

    /** Discovery is bounded and does not implicitly track or index every remote branch. */
    public List<RemoteBranch> discoverBranches(String url, ResolvedCredential credential) {
        String output =
                run(
                        List.of(
                                "-c",
                                "http.followRedirects=false",
                                "ls-remote",
                                "--heads",
                                url),
                        null,
                        credential,
                        60,
                        remoteTargets.allowsInsecureTls(url));
        List<RemoteBranch> branches =
                output.lines()
                        .filter(line -> !line.isBlank())
                        .limit(MAX_DISCOVERED_BRANCHES + 1L)
                        .map(
                                line -> {
                                    String[] fields = line.split("\\s+", 2);
                                    if (fields.length != 2
                                            || !fields[0].matches("[0-9a-fA-F]{40,64}")
                                            || !fields[1].startsWith("refs/heads/")) {
                                        throw new IllegalStateException("远程分支列表格式无效");
                                    }
                                    String name =
                                            com.analyzercoder.infrastructure.repository
                                                    .GitBranchSnapshotFactory.validateBranch(
                                                    fields[1].substring(11));
                                    return new RemoteBranch(name, fields[0]);
                                })
                        .sorted(java.util.Comparator.comparing(RemoteBranch::name))
                        .toList();
        if (branches.size() > MAX_DISCOVERED_BRANCHES) {
            throw new IllegalStateException(
                    "远程分支数量超过 " + MAX_DISCOVERED_BRANCHES + " 条，请清理无用分支后重试");
        }
        return branches;
    }

    /** Fetch into a generated private ref, never reset/checkout or rewrite a user's branch. */
    public String fetchBranch(
            Path worktree, String url, String branch, ResolvedCredential credential) {
        com.analyzercoder.infrastructure.repository.GitBranchSnapshotFactory.validateBranch(branch);
        String localRef = "refs/analyzer/branches/" + java.util.UUID.randomUUID();
        try {
            run(
                    List.of(
                            "-c",
                            "http.followRedirects=false",
                            "-c",
                            "core.hooksPath=" + disabledHooks(),
                            "fetch",
                            "--no-tags",
                            "--depth=1",
                            "--",
                            url,
                            "refs/heads/" + branch + ":" + localRef),
                    worktree,
                    credential,
                    180,
                    remoteTargets.allowsInsecureTls(url));
            String commit =
                    run(
                                    List.of(
                                            "rev-parse",
                                            "--verify",
                                            localRef + "^{commit}"),
                                    worktree,
                                    null,
                                    30)
                            .trim();
            if (!commit.matches("[0-9a-fA-F]{40,64}"))
                throw new IllegalStateException("无法确认远程分支提交");
            return commit;
        } finally {
            run(
                    List.of(
                            "-c",
                            "core.hooksPath=" + disabledHooks(),
                            "update-ref",
                            "-d",
                            localRef),
                    worktree,
                    null,
                    30);
        }
    }

    private static String disabledHooks() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "NUL"
                : "/dev/null";
    }

    public void validate(String url, ResolvedCredential credential) {
        // Validate transport and authentication without requiring a default branch or HEAD.
        // An empty GitLab repository is reachable and its credential can still be valid.
        run(
                List.of(
                        "-c",
                        "http.followRedirects=false",
                        "ls-remote",
                        "--heads",
                        url),
                null,
                credential,
                45,
                remoteTargets.allowsInsecureTls(url));
    }

    public void cloneRepository(
            String url, String branch, Path target, ResolvedCredential credential) {
        List<String> arguments = new ArrayList<>(List.of("clone", "--depth", "1"));
        if (branch != null && !branch.isBlank()) {
            arguments.addAll(List.of("--branch", branch));
        }
        arguments.add(url);
        arguments.add(target.toString());
        run(arguments, null, credential, 180, remoteTargets.allowsInsecureTls(url));
    }

    public void syncRepository(
            Path worktree, String url, String branch, ResolvedCredential credential) {
        run(
                List.of("fetch", "--prune", "origin"),
                worktree,
                credential,
                180,
                remoteTargets.allowsInsecureTls(url));
        String remoteRef = branch == null || branch.isBlank() ? "origin/HEAD" : "origin/" + branch;
        run(List.of("reset", "--hard", remoteRef), worktree, credential, 60);
    }

    /** 仅把 PR/MR Head 写入隔离的本地引用，不切换分支，也不改变当前工作区或发布快照。 */
    public String fetchReviewHead(
            Path worktree, String provider, long number, ResolvedCredential credential) {
        return fetchReviewHead(worktree, null, provider, number, credential);
    }

    public String fetchReviewHead(
            Path worktree,
            String repositoryUrl,
            String provider,
            long number,
            ResolvedCredential credential) {
        if (worktree == null || number < 1) {
            throw new IllegalArgumentException("PR/MR 审查引用不完整");
        }
        String normalizedProvider = provider == null ? "" : provider.trim().toUpperCase();
        String remoteRef;
        String localRef;
        switch (normalizedProvider) {
            case "GITHUB" -> {
                remoteRef = "refs/pull/" + number + "/head";
                localRef = "refs/analyzer/reviews/github/" + number + "/head";
            }
            case "GITLAB" -> {
                remoteRef = "refs/merge-requests/" + number + "/head";
                localRef = "refs/analyzer/reviews/gitlab/" + number + "/head";
            }
            default -> throw new IllegalArgumentException("不支持的 PR/MR 提供方");
        }
        run(
                List.of(
                        "fetch",
                        "--no-tags",
                        "--force",
                        "--depth=64",
                        "origin",
                        "+" + remoteRef + ":" + localRef),
                worktree,
                credential,
                180,
                remoteTargets.allowsInsecureTls(repositoryUrl));
        String commit =
                run(
                                List.of(
                                        "rev-parse",
                                        "--verify",
                                        localRef + "^{commit}"),
                                worktree,
                                null,
                                30)
                        .trim();
        if (!commit.matches("(?i)[0-9a-f]{40,64}")) {
            throw new IllegalStateException("无法确认 PR/MR Head 提交");
        }
        return commit;
    }

    private String run(
            List<String> arguments, Path cwd, ResolvedCredential credential, int seconds) {
        return run(arguments, cwd, credential, seconds, false);
    }

    private String run(
            List<String> arguments,
            Path cwd,
            ResolvedCredential credential,
            int seconds,
            boolean insecureTls) {
        Path askPassRoot = null;
        Process process = null;
        Path outputFile = null;
        try {
            Path askPass = null;
            if (credential != null) {
                askPassRoot = Files.createTempDirectory("analyzer-git-askpass-");
                askPass = createAskPass(askPassRoot);
            }
            List<String> command = commandFor(arguments, insecureTls);
            outputFile = Files.createTempFile("analyzer-git-output-", ".log");
            java.lang.ProcessBuilder builder =
                    new java.lang.ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .redirectOutput(outputFile.toFile());
            if (cwd != null) {
                builder.directory(cwd.toFile());
            }
            GitRuntimePolicy.sanitizeEnvironment(builder.environment());
            if (credential != null) {
                // Older Git versions reject an empty `-c credential.helper=` value. Isolate the
                // child process from machine/user Git configuration instead, so cached helpers
                // cannot override the credential selected in the application.
                builder.environment().put("HOME", askPassRoot.toString());
                builder.environment().put("XDG_CONFIG_HOME", askPassRoot.toString());
                builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
                builder.environment().put("GIT_ASKPASS", askPass.toString());
                builder.environment().put("GIT_ASKPASS_REQUIRE", "force");
                builder.environment().put("ANALYZER_GIT_USERNAME", credential.username());
                builder.environment().put("ANALYZER_GIT_SECRET", credential.secret());
            }
            process = builder.start();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git 操作超时");
            }
            if (Files.size(outputFile) > 2 * 1024 * 1024)
                throw new IllegalStateException("Git 输出超过限制，请缩小远程仓库范围");
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                String diagnostic = safeDiagnostic(output);
                log.warn(
                        "Git remote command failed: exitCode={}, credentialProvided={}, tlsVerificationDisabled={}, output={}",
                        process.exitValue(),
                        credential != null,
                        insecureTls,
                        diagnostic);
                throw new IllegalStateException(failureMessage(output, insecureTls));
            }
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("无法执行带凭据的 Git 操作", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git 操作被中断", exception);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (outputFile != null)
                try {
                    Files.deleteIfExists(outputFile);
                } catch (IOException ignored) {
                }
            deleteTree(askPassRoot);
        }
    }

    static List<String> commandFor(List<String> arguments, boolean insecureTls) {
        ArrayList<String> command = new ArrayList<>();
        command.add("git");
        if (insecureTls) {
            command.add("-c");
            command.add("http.sslVerify=false");
        }
        command.addAll(arguments);
        return command;
    }

    private static Path createAskPass(Path root) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = root.resolve(windows ? "askpass.cmd" : "askpass.sh");
        String content =
                windows
                        ? "@echo off\r\n"
                                + "echo %~1 | findstr /I \"Username\" >nul\r\n"
                                + "if %errorlevel%==0 (echo %ANALYZER_GIT_USERNAME%) else (echo"
                                + " %ANALYZER_GIT_SECRET%)\r\n"
                        : "#!/bin/sh\n"
                                + "case \"$1\" in *Username*) printf '%s\\n"
                                + "' \"$ANALYZER_GIT_USERNAME\";; *) printf '%s\\n"
                                + "' \"$ANALYZER_GIT_SECRET\";; esac\n";
        Files.writeString(script, content, StandardCharsets.UTF_8);
        if (!windows) {
            Files.setPosixFilePermissions(
                    script,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        }
        return script;
    }

    static String failureMessage(String output, boolean insecureTls) {
        String text = output == null ? "" : output.toLowerCase();
        if (text.contains("authentication failed")
                || text.contains("access denied")
                || text.contains("http basic: access denied")
                || text.contains("returned error: 401")
                || text.contains("returned error: 403")
                || text.contains("not allowed to download code")
                || text.contains("could not read username")
                || text.contains("could not read password")
                || text.contains("permission denied")) {
            return "远程仓库身份验证失败，请检查令牌有效期、read_repository 权限和项目访问权限";
        }
        if (text.contains("repository not found")) {
            return "远程仓库不存在，或当前凭据没有访问权限";
        }
        if (text.contains("could not resolve host")) {
            return "无法解析远程仓库域名";
        }
        if (text.contains("ssl certificate problem")
                || text.contains("certificate issuer")
                || text.contains("certificate verification failed")
                || text.contains("peer's certificate")
                || text.contains("not trusted")) {
            return insecureTls
                    ? "远程仓库 TLS 连接失败（该主机已关闭证书校验，请检查代理或 Git SSL 后端）"
                    : "远程仓库证书校验失败（该主机未命中 insecure-tls-hosts）";
        }
        if (text.contains("cannot run") && text.contains("askpass")) {
            return "Git 凭据助手无法执行，请检查后端临时目录是否允许执行脚本";
        }
        String detail = lastDiagnosticLine(output);
        String tlsState = insecureTls ? "已关闭证书校验" : "仍启用证书校验";
        return detail.isBlank()
                ? "Git 凭据检测或仓库下载失败（" + tlsState + "）"
                : "Git 操作失败（" + tlsState + "）：" + detail;
    }

    private static String safeDiagnostic(String output) {
        String value = output == null ? "" : output;
        value = value.replaceAll("(?i)(https?://)[^\\s/@]+@", "$1<redacted>@");
        value = value.replaceAll("(?i)glpat-[a-z0-9_-]+", "<redacted-token>");
        value = value.replaceAll("[\\r\\n]+", " | ").trim();
        return value.substring(0, Math.min(1000, value.length()));
    }

    private static String lastDiagnosticLine(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String[] lines = output.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = safeDiagnostic(lines[index]).trim();
            if (!line.isBlank()) {
                return line.substring(0, Math.min(180, line.length()));
            }
        }
        return "";
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
        } catch (IOException ignored) {
        }
    }

    public record ResolvedCredential(String username, String secret) {}
}
