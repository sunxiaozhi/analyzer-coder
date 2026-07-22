package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RepositorySourceImportService {
    private final RegisterRepositoryUseCase repositories;
    private final RepositoryMapper mapper;
    private final Path importRoot;
    private final Path managedRepositoriesRoot;

    public RepositorySourceImportService(RegisterRepositoryUseCase repositories,RepositoryMapper mapper,
        @Value("${app.repository.import-root:${java.io.tmpdir}/analyzer-coder/staging/imports}") String root,
        @Value("${app.repository.snapshot-root:${java.io.tmpdir}/analyzer-coder/repositories}") String repositoriesRoot) {
        this.repositories=repositories;this.mapper=mapper;
        this.importRoot=Path.of(root).toAbsolutePath().normalize();
        this.managedRepositoriesRoot=Path.of(repositoriesRoot).toAbsolutePath().normalize();
    }

    public CodeRepository importRemote(String name,String url,String branch,RepositorySourceType type,UUID ownerAccountId) {
        if(type!=RepositorySourceType.REMOTE_GIT&&type!=RepositorySourceType.GITLAB)throw new IllegalArgumentException("来源类型必须是 REMOTE_GIT 或 GITLAB");
        URI uri=URI.create(url);
        if(!List.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("远程地址必须是无内嵌凭据的 HTTP(S) Git URL");
        Path target=allocate();
        try {
            runGit(branch==null||branch.isBlank()?List.of("clone","--depth","1",url,target.toString()):List.of("clone","--depth","1","--branch",branch,url,target.toString()),null,180);
            return registerImported(name,target,type,false,ownerAccountId);
        } catch(RuntimeException exception) { deleteTree(target); throw exception; }
    }

    public CodeRepository importZip(String name,MultipartFile upload,UUID ownerAccountId) {
        String filename=upload.getOriginalFilename();
        if(filename==null||!filename.toLowerCase().endsWith(".zip"))throw new IllegalArgumentException("仅支持 ZIP 文件");
        Path target=allocate();
        try {
            Files.createDirectories(target);extract(upload,target);
            runGit(List.of("init"),target,30);runGit(List.of("config","user.email","platform@local"),target,10);
            runGit(List.of("config","user.name","Code Knowledge Platform"),target,10);runGit(List.of("add","."),target,60);
            runGit(List.of("commit","--allow-empty","-m","Imported ZIP snapshot"),target,60);
            return registerImported(name,target,RepositorySourceType.ZIP,true,ownerAccountId);
        } catch(IOException exception) { deleteTree(target); throw new IllegalStateException("ZIP 导入失败",exception); }
          catch(RuntimeException exception) { deleteTree(target); throw exception; }
    }

    private CodeRepository registerImported(String name,Path staging,RepositorySourceType type,boolean hideGitVersion,UUID ownerAccountId) {
        CodeRepository created=repositories.registerManaged(new RegisterRepositoryCommand(name,staging.toString(),ownerAccountId));
        Path worktree=managedRepositoriesRoot.resolve(created.id().value().toString()).resolve("worktree").normalize();
        if(!worktree.startsWith(managedRepositoriesRoot))throw new IllegalStateException("受管工作副本路径越界");
        try {
            Files.createDirectories(worktree.getParent());
            Files.move(staging,worktree,StandardCopyOption.ATOMIC_MOVE);
            if(mapper.updateManagedSource(created.id().value(),worktree.toString(),type.name(),hideGitVersion)!=1)throw new IllegalStateException("无法发布受管仓库工作副本");
            return repositories.get(created.id());
        } catch(IOException|RuntimeException exception) {
            try { repositories.delete(created.id()); } catch(RuntimeException cleanup) { exception.addSuppressed(cleanup); }
            deleteTree(staging);
            throw exception instanceof RuntimeException runtime?runtime:new IllegalStateException("无法发布受管仓库工作副本",exception);
        }
    }

    private Path allocate() {
        try {
            Files.createDirectories(importRoot);Path target=importRoot.resolve(UUID.randomUUID().toString()).normalize();
            if(!target.startsWith(importRoot))throw new IllegalStateException("导入路径越界");return target;
        } catch(IOException exception) { throw new IllegalStateException("无法创建导入目录",exception); }
    }

    private static void extract(MultipartFile upload,Path root)throws IOException {
        long total=0;int count=0;
        try(InputStream raw=upload.getInputStream();ZipInputStream zip=new ZipInputStream(raw)) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if(++count>20000)throw new IllegalArgumentException("ZIP 文件数超过 20000");
                Path out=root.resolve(entry.getName()).normalize();if(!out.startsWith(root))throw new IllegalArgumentException("ZIP 包含越界路径");
                if(entry.isDirectory()){Files.createDirectories(out);continue;}Files.createDirectories(out.getParent());
                long copied=Files.copy(zip,out,StandardCopyOption.REPLACE_EXISTING);total+=copied;
                if(copied>20L*1024*1024||total>500L*1024*1024)throw new IllegalArgumentException("ZIP 解压大小超过限制");
            }
        }
    }

    private static void runGit(List<String> args,Path cwd,int seconds) {
        try {
            java.util.ArrayList<String> command=new java.util.ArrayList<>();command.add("git");command.addAll(args);
            ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);if(cwd!=null)builder.directory(cwd.toFile());builder.environment().put("GIT_TERMINAL_PROMPT","0");
            Process process=builder.start();String output=new String(process.getInputStream().readNBytes(8192));
            if(!process.waitFor(seconds,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Git 操作超时");}
            if(process.exitValue()!=0)throw new IllegalStateException("Git 操作失败: "+output.replaceAll("https?://[^\\s]+","[remote]"));
        } catch(IOException exception) { throw new IllegalStateException("无法执行 Git",exception); }
          catch(InterruptedException exception) { Thread.currentThread().interrupt();throw new IllegalStateException("Git 操作被中断",exception); }
    }

    private static void deleteTree(Path target) {
        if(target==null||!Files.exists(target))return;
        try(var paths=Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path->{try{path.toFile().setWritable(true,false);Files.deleteIfExists(path);}catch(IOException exception){throw new IllegalStateException("无法清理导入临时目录",exception);}});
        } catch(IOException exception) { throw new IllegalStateException("无法清理导入临时目录",exception); }
    }
}