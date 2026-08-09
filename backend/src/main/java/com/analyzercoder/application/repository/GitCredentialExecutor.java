package com.analyzercoder.application.repository;

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
import org.springframework.stereotype.Component;

@Component
public class GitCredentialExecutor {
    public void validate(String url, ResolvedCredential credential) {
        run(List.of("ls-remote", "--exit-code", url, "HEAD"), null, credential, 45);
    }

    public void cloneRepository(String url, String branch, Path target, ResolvedCredential credential) {
        List<String> arguments = new ArrayList<>(List.of("clone", "--depth", "1"));
        if (branch != null && !branch.isBlank()) arguments.addAll(List.of("--branch", branch));
        arguments.add(url);
        arguments.add(target.toString());
        run(arguments, null, credential, 180);
    }

    public void syncRepository(Path worktree, String branch, ResolvedCredential credential) {
        run(List.of("fetch", "--prune", "origin"), worktree, credential, 180);
        String remoteRef = branch == null || branch.isBlank() ? "origin/HEAD" : "origin/" + branch;
        run(List.of("reset", "--hard", remoteRef), worktree, credential, 60);
    }

    private void run(List<String> arguments, Path cwd, ResolvedCredential credential, int seconds) {
        Path askPassRoot = null;
        try {
            Path askPass = null;
            if (credential != null) {
                askPassRoot = Files.createTempDirectory("analyzer-git-askpass-");
                askPass = createAskPass(askPassRoot);
            }
            ArrayList<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(arguments);
            java.lang.ProcessBuilder builder = new java.lang.ProcessBuilder(command).redirectErrorStream(true);
            if (cwd != null) builder.directory(cwd.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            if (credential != null) {
                builder.environment().put("GIT_ASKPASS", askPass.toString());
                builder.environment().put("ANALYZER_GIT_USERNAME", credential.username());
                builder.environment().put("ANALYZER_GIT_SECRET", credential.secret());
            }
            Process process = builder.start();
            String output = new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8);
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git 操作超时");
            }
            if (process.exitValue() != 0) throw new IllegalStateException(failureMessage(output));
        } catch (IOException exception) {
            throw new IllegalStateException("无法执行带凭据的 Git 操作", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git 操作被中断", exception);
        } finally {
            deleteTree(askPassRoot);
        }
    }

    private static Path createAskPass(Path root) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = root.resolve(windows ? "askpass.cmd" : "askpass.sh");
        String content = windows
            ? "@echo off\r\necho %~1 | findstr /I \"Username\" >nul\r\n"
                + "if %errorlevel%==0 (echo %ANALYZER_GIT_USERNAME%) else (echo %ANALYZER_GIT_SECRET%)\r\n"
            : "#!/bin/sh\ncase \"$1\" in *Username*) printf '%s\\n' \"$ANALYZER_GIT_USERNAME\";; "
                + "*) printf '%s\\n' \"$ANALYZER_GIT_SECRET\";; esac\n";
        Files.writeString(script, content, StandardCharsets.UTF_8);
        if (!windows) {
            Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        }
        return script;
    }

    private static String failureMessage(String output) {
        String text = output == null ? "" : output.toLowerCase();
        if (text.contains("authentication failed") || text.contains("access denied")
            || text.contains("could not read username") || text.contains("permission denied")) {
            return "远程仓库身份验证失败，请检查凭据权限和有效期";
        }
        if (text.contains("repository not found")) return "远程仓库不存在，或当前凭据没有访问权限";
        if (text.contains("could not resolve host")) return "无法解析远程仓库域名";
        if (text.contains("ssl certificate problem")) return "远程仓库证书校验失败";
        return "Git 凭据检测或仓库下载失败";
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    public record ResolvedCredential(String username, String secret) {}
}
