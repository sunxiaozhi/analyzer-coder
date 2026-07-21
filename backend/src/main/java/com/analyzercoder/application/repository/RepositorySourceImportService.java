package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.RepositorySourceType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RepositorySourceImportService {
    private final RegisterRepositoryUseCase repositories; private final JdbcTemplate jdbc; private final Path importRoot;
    public RepositorySourceImportService(RegisterRepositoryUseCase repositories,JdbcTemplate jdbc,
        @Value("${app.repository.import-root:C:/tmp/analyzer-coder-imports}")String root){this.repositories=repositories;this.jdbc=jdbc;this.importRoot=Path.of(root).toAbsolutePath().normalize();}

    public CodeRepository importRemote(String name,String url,String branch,RepositorySourceType type){
        if(type!=RepositorySourceType.REMOTE_GIT&&type!=RepositorySourceType.GITLAB)throw new IllegalArgumentException("来源类型必须是 REMOTE_GIT 或 GITLAB");
        URI uri=URI.create(url);if(!List.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("远程地址必须是无内嵌凭据的 HTTP(S) Git URL");
        Path target=allocate();runGit(branch==null||branch.isBlank()?List.of("clone","--depth","1",url,target.toString()):List.of("clone","--depth","1","--branch",branch,url,target.toString()),null,180);
        return registerImported(name,target,type,false);
    }

    public CodeRepository importZip(String name,MultipartFile upload){
        String filename=upload.getOriginalFilename();if(filename==null||!filename.toLowerCase().endsWith(".zip"))throw new IllegalArgumentException("仅支持 ZIP 文件");
        Path target=allocate();try{Files.createDirectories(target);extract(upload,target);runGit(List.of("init"),target,30);runGit(List.of("config","user.email","platform@local"),target,10);runGit(List.of("config","user.name","Code Knowledge Platform"),target,10);runGit(List.of("add","."),target,60);runGit(List.of("commit","--allow-empty","-m","Imported ZIP snapshot"),target,60);return registerImported(name,target,RepositorySourceType.ZIP,true);}catch(IOException e){throw new IllegalStateException("ZIP 导入失败",e);}
    }
    private CodeRepository registerImported(String name,Path target,RepositorySourceType type,boolean hideGitVersion){CodeRepository created=repositories.register(new RegisterRepositoryCommand(name,target.toString()));jdbc.update("UPDATE repositories SET source_type=?,default_branch=CASE WHEN ? THEN NULL ELSE default_branch END,current_commit=CASE WHEN ? THEN NULL ELSE current_commit END WHERE id=?",type.name(),hideGitVersion,hideGitVersion,created.id().value());return repositories.get(created.id());}
    private Path allocate(){try{Files.createDirectories(importRoot);return importRoot.resolve(UUID.randomUUID().toString()).normalize();}catch(IOException e){throw new IllegalStateException("无法创建导入目录",e);}}
    private static void extract(MultipartFile upload,Path root)throws IOException{long total=0;int count=0;try(InputStream raw=upload.getInputStream();ZipInputStream zip=new ZipInputStream(raw)){ZipEntry entry;while((entry=zip.getNextEntry())!=null){if(++count>20000)throw new IllegalArgumentException("ZIP 文件数超过 20000");Path out=root.resolve(entry.getName()).normalize();if(!out.startsWith(root))throw new IllegalArgumentException("ZIP 包含越界路径");if(entry.isDirectory()){Files.createDirectories(out);continue;}Files.createDirectories(out.getParent());long copied=Files.copy(zip,out,StandardCopyOption.REPLACE_EXISTING);total+=copied;if(copied>20L*1024*1024||total>500L*1024*1024)throw new IllegalArgumentException("ZIP 解压大小超过限制");}}}
    private static void runGit(List<String> args,Path cwd,int seconds){try{java.util.ArrayList<String> command=new java.util.ArrayList<>();command.add("git");command.addAll(args);ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);if(cwd!=null)builder.directory(cwd.toFile());builder.environment().put("GIT_TERMINAL_PROMPT","0");Process process=builder.start();String output=new String(process.getInputStream().readNBytes(8192));if(!process.waitFor(seconds,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Git 操作超时");}if(process.exitValue()!=0)throw new IllegalStateException("Git 操作失败: "+output.replaceAll("https?://[^\\s]+","[remote]"));}catch(IOException|InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("无法执行 Git",e);}}
}
