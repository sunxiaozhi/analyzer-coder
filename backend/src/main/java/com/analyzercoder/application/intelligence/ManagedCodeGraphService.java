package com.analyzercoder.application.intelligence;

import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Primary
@Service
public class ManagedCodeGraphService extends CodeGraphService {
    private final CodeGraphArtifactMapper mapper; private final ObjectMapper json; private final String executable; private final Path root; private final long timeoutMinutes;
    public ManagedCodeGraphService(CodeGraphArtifactMapper mapper,ObjectMapper json,@Value("${app.codegraph.executable:codegraph}")String executable,
        @Value("${app.codegraph.timeout-minutes:10}")long timeoutMinutes,
        @Value("${app.codegraph.artifact-root:${java.io.tmpdir}/analyzer-coder/codegraph}")String root){super(mapper,json,executable,timeoutMinutes);this.mapper=mapper;this.json=json;this.executable=executable;this.timeoutMinutes=timeoutMinutes;this.root=Path.of(root).toAbsolutePath().normalize();}

    @Override @Transactional public Artifact build(UUID repoId){Version version=version(repoId);UUID artifactId=UUID.randomUUID();Path project=root.resolve(repoId.toString()).resolve("codegraph").resolve(version.snapshotId().toString()).resolve(artifactId.toString()).resolve("project");try{copySnapshot(version.snapshotPath(),project);String output=run(List.of("init",project.toString()),timeoutMinutes*60);int nodes=metric(output,"nodes"),edges=metric(output,"edges");String cli=run(List.of("--version"),30).trim();Path marker=project.resolve(".codegraph");if(!Files.isDirectory(marker))throw new IllegalStateException("CodeGraph 未生成预期产物目录");mapper.retirePublished(repoId);mapper.insertPublished(new CodeGraphArtifactRow(artifactId,repoId,version.snapshotId(),cli,"PUBLISHED",marker.toString(),nodes,edges));return new Artifact(artifactId,repoId,version.snapshotId(),cli,"PUBLISHED",marker.toString(),nodes,edges);}catch(IOException e){throw new IllegalStateException("无法创建 CodeGraph 分析副本",e);}}

    @Override public IntelligenceService.GraphResult impact(UUID repoId,String symbol,int depth){Version version=version(repoId);Artifact artifact=published(repoId,version.snapshotId());Path project=Path.of(artifact.artifactPath()).getParent();String output=run(List.of("impact","-p",project.toString(),"-d",String.valueOf(Math.max(1,Math.min(depth,5))),"-j",symbol),120);try{JsonNode root=json.readTree(output);Map<String,IntelligenceService.GraphNode>nodes=new LinkedHashMap<>();nodes.put(symbol,new IntelligenceService.GraphNode(symbol,0,true));List<IntelligenceService.GraphEdge>edges=new ArrayList<>();for(JsonNode item:root.path("affected")){String label=item.path("name").asText()+" @ "+item.path("filePath").asText()+":"+item.path("startLine").asInt();if(!label.startsWith(symbol+" @")){nodes.putIfAbsent(label,new IntelligenceService.GraphNode(label,1,false));edges.add(new IntelligenceService.GraphEdge(symbol,label,"AFFECTS"));}}int count=root.path("nodeCount").asInt(nodes.size());return new IntelligenceService.GraphResult(new ArrayList<>(nodes.values()),edges,count>100?"HIGH":count>30?"MEDIUM":"LOW",List.of("CodeGraph "+artifact.cliVersion()+" 确定性静态分析","动态反射和运行时分派可能无法确认","快照 "+version.snapshotId()));}catch(IOException e){throw new IllegalStateException("CodeGraph 返回了无法解析的结果",e);}}
    @Override public Artifact latest(UUID repoId){Version version=version(repoId);return artifact(mapper.findLatest(repoId,version.snapshotId()));}
    private Artifact published(UUID repoId,UUID snapshot){Artifact result=artifact(mapper.findPublished(repoId,snapshot));if(result==null)throw new IllegalStateException("当前快照尚未发布 CodeGraph 产物");return result;}
    private Version version(UUID id){var row=mapper.findRepositoryVersion(id);if(row==null)throw new IllegalArgumentException("仓库不存在");return new Version(row.snapshotId(),Path.of(row.snapshotPath()));}
    private static void copySnapshot(Path source,Path target)throws IOException{Files.createDirectories(target);try(var paths=Files.walk(source)){for(Path path:paths.filter(p->!p.equals(source)).toList()){Path relative=source.relativize(path);if(relative.getNameCount()>0&&relative.getName(0).toString().equals(".codegraph"))continue;Path out=target.resolve(relative).normalize();if(!out.startsWith(target))throw new IOException("快照路径越界");if(Files.isSymbolicLink(path))continue;if(Files.isDirectory(path))Files.createDirectories(out);else{Files.createDirectories(out.getParent());Files.copy(path,out,StandardCopyOption.COPY_ATTRIBUTES);}}}}
    private String run(List<String>args,long seconds){try{List<String>command=new ArrayList<>();if(isWindows()||executable.toLowerCase().endsWith(".cmd")||executable.toLowerCase().endsWith(".bat")){command.add("cmd.exe");command.add("/d");command.add("/s");command.add("/c");command.add(executable);}else command.add(executable);command.addAll(args);java.lang.ProcessBuilder builder=new java.lang.ProcessBuilder(command).redirectErrorStream(true);builder.environment().put("NO_COLOR","1");Process process=builder.start();ByteArrayOutputStream buffer=new ByteArrayOutputStream();Thread reader=new Thread(()->{try{process.getInputStream().transferTo(buffer);}catch(IOException ignored){}},"managed-codegraph-output");reader.setDaemon(true);reader.start();if(!process.waitFor(seconds,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("CodeGraph 执行超时");}reader.join(5000);String output=buffer.toString(StandardCharsets.UTF_8);if(process.exitValue()!=0)throw new IllegalStateException("CodeGraph 执行失败: "+output.substring(0,Math.min(output.length(),1000)));return output;}catch(IOException e){throw new IllegalStateException("未找到 CodeGraph CLI",e);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("CodeGraph 执行被中断",e);}}
    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase().contains("win");}
    private static int metric(String output,String label){Matcher matcher=Pattern.compile("([0-9,]+)\\s+"+label,Pattern.CASE_INSENSITIVE).matcher(output);return matcher.find()?Integer.parseInt(matcher.group(1).replace(",","")):0;}
    private record Version(UUID snapshotId,Path snapshotPath){}
}
