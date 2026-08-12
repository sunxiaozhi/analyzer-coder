package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.security.AuthenticatedAccount;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 导入远程或上传的仓库源码，集中执行大小、路径、压缩包与来源安全校验。 */
@Service
public class RepositorySourceImportService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(RepositorySourceImportService.class);
    private final RegisterRepositoryUseCase repositories;
    private final RepositoryMapper mapper;
    private final Path importRoot;
    private final Path managedRepositoriesRoot;
    private final RepositoryCredentialService credentials;
    private final GitCredentialExecutor credentialGit;

    public RepositorySourceImportService(
            RegisterRepositoryUseCase repositories,
            RepositoryMapper mapper,
            RepositoryCredentialService credentials,
            GitCredentialExecutor credentialGit,
            @Value("${app.repository.import-root:${java.io.tmpdir}/analyzer-coder/staging/imports}")
                    String root,
            @Value("${app.repository.snapshot-root:${java.io.tmpdir}/analyzer-coder/repositories}")
                    String repositoriesRoot) {
        this.repositories = repositories;
        this.mapper = mapper;
        this.credentials = credentials;
        this.credentialGit = credentialGit;
        this.importRoot = Path.of(root).toAbsolutePath().normalize();
        this.managedRepositoriesRoot = Path.of(repositoriesRoot).toAbsolutePath().normalize();
    }

    public CodeRepository importRemote(
            String name,
            String url,
            String branch,
            RepositorySourceType type,
            UUID credentialId,
            AuthenticatedAccount owner) {
        if (type != RepositorySourceType.REMOTE_GIT && type != RepositorySourceType.GITLAB) {
            throw new IllegalArgumentException("来源类型必须是 REMOTE_GIT 或 GITLAB");
        }
        URI uri = URI.create(url);
        if (!List.of("https", "http").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("远程地址必须是无内嵌凭据的 HTTP(S) Git URL");
        }
        Path target = allocate();
        try {
            if (credentialId == null) {
                runGit(
                        branch == null || branch.isBlank()
                                ? List.of("clone", "--depth", "1", url, target.toString())
                                : List.of(
                                        "clone",
                                        "--depth",
                                        "1",
                                        "--branch",
                                        branch,
                                        url,
                                        target.toString()),
                        null,
                        180);
            } else {
                var credential = credentials.resolve(owner, credentialId, url);
                credentialGit.cloneRepository(url, branch, target, credential.value());
            }
            CodeRepository repository =
                    registerImported(name, target, type, false, url, owner.id());
            if (credentialId != null) {
                credentials.bind(repository.id().value(), credentialId, owner.id());
            }
            return repository;
        } catch (RuntimeException exception) {
            deleteTree(target);
            throw exception;
        }
    }

    public CodeRepository importZip(String name, MultipartFile upload, UUID ownerAccountId) {
        String filename = upload.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("仅支持 ZIP 文件");
        }
        Path target = allocate();
        try {
            Files.createDirectories(target);
            extract(upload, target);
            runGit(List.of("init"), target, 30);
            runGit(List.of("config", "user.email", "platform@local"), target, 10);
            runGit(List.of("config", "user.name", "Code Knowledge Platform"), target, 10);
            runGit(List.of("add", "."), target, 60);
            runGit(List.of("commit", "--allow-empty", "-m", "Imported ZIP snapshot"), target, 60);
            return registerImported(
                    name, target, RepositorySourceType.ZIP, true, null, ownerAccountId);
        } catch (IOException exception) {
            deleteTree(target);
            throw new IllegalStateException("ZIP 导入失败", exception);
        } catch (RuntimeException exception) {
            deleteTree(target);
            throw exception;
        }
    }

    private CodeRepository registerImported(
            String name,
            Path staging,
            RepositorySourceType type,
            boolean hideGitVersion,
            String remoteUrl,
            UUID ownerAccountId) {
        CodeRepository created =
                repositories.registerManaged(
                        new RegisterRepositoryCommand(name, staging.toString(), ownerAccountId));
        Path worktree =
                managedRepositoriesRoot
                        .resolve(created.id().value().toString())
                        .resolve("worktree")
                        .normalize();
        if (!worktree.startsWith(managedRepositoriesRoot)) {
            throw new IllegalStateException("受管工作副本路径越界");
        }
        try {
            Files.createDirectories(worktree.getParent());
            Files.move(staging, worktree, StandardCopyOption.ATOMIC_MOVE);
            if (mapper.updateManagedSource(
                            created.id().value(),
                            worktree.toString(),
                            type.name(),
                            remoteUrl,
                            hideGitVersion)
                    != 1) {
                throw new IllegalStateException("无法发布受管仓库工作副本");
            }
            return repositories.get(created.id());
        } catch (IOException | RuntimeException exception) {
            try {
                repositories.delete(created.id());
            } catch (RuntimeException cleanup) {
                exception.addSuppressed(cleanup);
            }
            deleteTree(staging);
            throw exception instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("无法发布受管仓库工作副本", exception);
        }
    }

    private Path allocate() {
        try {
            Files.createDirectories(importRoot);
            Path target = importRoot.resolve(UUID.randomUUID().toString()).normalize();
            if (!target.startsWith(importRoot)) {
                throw new IllegalStateException("导入路径越界");
            }
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建导入目录", exception);
        }
    }

    private static void extract(MultipartFile upload, Path root) throws IOException {
        long total = 0;
        int count = 0;
        try (InputStream raw = upload.getInputStream();
                ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 20000) {
                    throw new IllegalArgumentException("ZIP 文件数超过 20000");
                }
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IllegalArgumentException("ZIP 包含越界路径");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                long copied = Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                total += copied;
                if (copied > 20L * 1024 * 1024 || total > 500L * 1024 * 1024) {
                    throw new IllegalArgumentException("ZIP 解压大小超过限制");
                }
            }
        }
    }

    private static void runGit(List<String> args, Path cwd, int seconds) {
        try {
            java.util.ArrayList<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.addAll(args);
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (cwd != null) {
                builder.directory(cwd.toFile());
            }
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = builder.start();
            String output = new String(process.getInputStream().readNBytes(8192));
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git 操作超时");
            }
            if (process.exitValue() != 0) {
                String sanitized = output.replaceAll("https?://[^\\s]+", "[远程地址]");
                LOGGER.warn("Git 命令执行失败：{}", sanitized.strip());
                throw new IllegalStateException(gitFailureMessage(args, sanitized));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法执行 Git", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git 操作被中断", exception);
        }
    }

    public CodeRepository importRemoteQueued(
            String name,
            String url,
            String branch,
            RepositorySourceType type,
            UUID credentialId,
            UUID ownerAccountId) {
        if (type != RepositorySourceType.REMOTE_GIT && type != RepositorySourceType.GITLAB) {
            throw new IllegalArgumentException("来源类型必须是 REMOTE_GIT 或 GITLAB");
        }
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("远程地址必须是无内嵌凭据的 HTTPS Git URL");
        }
        Path target = allocate();
        try {
            if (credentialId == null) {
                runGit(
                        branch == null || branch.isBlank()
                                ? List.of("clone", "--depth", "1", url, target.toString())
                                : List.of(
                                        "clone",
                                        "--depth",
                                        "1",
                                        "--branch",
                                        branch,
                                        url,
                                        target.toString()),
                        null,
                        180);
            } else {
                credentialGit.cloneRepository(
                        url,
                        branch,
                        target,
                        credentials.resolveInternal(credentialId, url).value());
            }
            CodeRepository repository =
                    registerImported(name, target, type, false, url, ownerAccountId);
            if (credentialId != null) {
                credentials.bind(repository.id().value(), credentialId, ownerAccountId);
            }
            return repository;
        } catch (RuntimeException exception) {
            deleteTree(target);
            throw exception;
        }
    }

    static String gitFailureMessage(List<String> args, String output) {
        String text = output == null ? "" : output.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("remote branch") && text.contains("not found")) {
            int branchIndex = args.indexOf("--branch");
            String branch =
                    branchIndex >= 0 && branchIndex + 1 < args.size()
                            ? args.get(branchIndex + 1)
                            : "指定";
            return "远程仓库中不存在分支“" + branch + "”，请确认分支名称后重试";
        }
        if (text.contains("repository not found")) {
            return "远程仓库不存在，或当前运行账号没有访问权限";
        }
        if (text.contains("authentication failed")
                || text.contains("could not read username")
                || text.contains("permission denied")
                || text.contains("access denied")) {
            return "远程仓库身份验证失败，请检查运行账号的 Git 凭据和仓库访问权限";
        }
        if (text.contains("could not resolve host") || text.contains("name or service not known")) {
            return "无法解析远程仓库域名，请检查仓库地址和 DNS 配置";
        }
        if (text.contains("connection timed out") || text.contains("operation timed out")) {
            return "连接远程仓库超时，请检查网络连接后重试";
        }
        if (text.contains("connection refused") || text.contains("failed to connect")) {
            return "无法连接远程仓库服务，请检查地址、端口和网络策略";
        }
        if (text.contains("ssl certificate problem")
                || text.contains("certificate verify failed")) {
            return "远程仓库的安全证书校验失败，请检查证书链和系统信任配置";
        }
        if (text.contains("not a git repository")) {
            return "目标地址不是有效的 Git 仓库";
        }
        if (text.contains("early eof")
                || text.contains("connection was reset")
                || text.contains("remote end hung up unexpectedly")) {
            return "下载仓库时网络连接意外中断，请稍后重试";
        }
        return "Git 操作失败，请检查仓库地址、分支、访问权限和网络配置";
    }

    private static void deleteTree(Path target) {
        if (target == null || !Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    path.toFile().setWritable(true, false);
                                    Files.deleteIfExists(path);
                                } catch (IOException exception) {
                                    throw new IllegalStateException("无法清理导入临时目录", exception);
                                }
                            });
        } catch (IOException exception) {
            throw new IllegalStateException("无法清理导入临时目录", exception);
        }
    }
}
