package com.analyzercoder.application.repository;
import com.analyzercoder.domain.repository.*;import com.analyzercoder.infrastructure.persistence.mapper.RepositoryImportJobMapper;
import com.analyzercoder.infrastructure.repository.RemoteRepositoryTargetPolicy;import com.analyzercoder.security.*;
import java.time.*;import java.util.*;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
@Service public class RepositoryImportJobService{
 private final RepositoryImportJobMapper mapper;private final RepositoryCredentialService credentials;private final RepositorySourceImportService imports;
 public RepositoryImportJobService(RepositoryImportJobMapper mapper,RepositoryCredentialService credentials,RepositorySourceImportService imports){this.mapper=mapper;this.credentials=credentials;this.imports=imports;}
 public JobView submit(AuthenticatedAccount actor,String name,String url,String branch,RepositorySourceType type,UUID credentialId){
  RemoteRepositoryTargetPolicy.requireAllowed(url);if(credentialId!=null)credentials.resolve(actor,credentialId,url);
  if(type!=RepositorySourceType.REMOTE_GIT&&type!=RepositorySourceType.GITLAB)throw new IllegalArgumentException("仅支持远程 Git/GitLab 后台导入");
  UUID id=UUID.randomUUID();mapper.insert(id,actor.id(),credentialId,type.name(),name.trim(),url,branch);return view(mapper.find(id));}
 public JobView get(AuthenticatedAccount actor,UUID id){Map<String,Object> row=mapper.find(id);requireVisible(actor,row);return view(row);}
 public List<JobView> list(AuthenticatedAccount actor){return mapper.list(actor.id(),actor.isSuperAdmin()).stream().map(this::view).toList();}
 public JobView cancel(AuthenticatedAccount actor,UUID id){if(mapper.requestCancel(id,actor.id(),actor.isSuperAdmin())!=1)throw new IllegalStateException("任务当前不能取消");return get(actor,id);}
 @Transactional public boolean processNext(){Map<String,Object> row=mapper.claim();if(row==null)return false;UUID id=uuid(row,"id");try{
   if(bool(row,"cancel_requested")){mapper.cancel(id);return true;}mapper.step(id,"cloning");
   CodeRepository repository=imports.importRemoteQueued(string(row,"repository_name"),string(row,"remote_url"),string(row,"branch"),RepositorySourceType.valueOf(string(row,"source_type")),uuid(row,"credential_id"),uuid(row,"account_id"));
   mapper.succeed(id,repository.id().value());return true;
  }catch(RuntimeException exception){mapper.fail(id,safe(exception));return false;}}
 private void requireVisible(AuthenticatedAccount actor,Map<String,Object> row){if(row==null)throw new IllegalArgumentException("导入任务不存在");if(!actor.isSuperAdmin()&&!actor.id().equals(uuid(row,"account_id")))throw new ApiSecurityException(403,"FORBIDDEN","无权查看该导入任务");}
 private JobView view(Map<String,Object> r){return new JobView(uuid(r,"id"),string(r,"source_type"),string(r,"repository_name"),string(r,"remote_url"),string(r,"branch"),string(r,"status"),string(r,"current_step"),string(r,"error_message"),uuid(r,"result_repository_id"),instant(r,"created_at"),instant(r,"started_at"),instant(r,"finished_at"));}
 private static String safe(RuntimeException e){String v=e.getMessage();return v==null?"导入失败":v.substring(0,Math.min(500,v.length()));}private static Object val(Map<String,Object>r,String k){Object v=r.get(k);return v==null?r.get(k.toUpperCase(Locale.ROOT)):v;}private static String string(Map<String,Object>r,String k){Object v=val(r,k);return v==null?null:v.toString();}private static UUID uuid(Map<String,Object>r,String k){Object v=val(r,k);return v==null?null:v instanceof UUID u?u:UUID.fromString(v.toString());}private static boolean bool(Map<String,Object>r,String k){Object v=val(r,k);return v instanceof Boolean b&&b;}private static Instant instant(Map<String,Object>r,String k){Object v=val(r,k);return v==null?null:v instanceof Instant i?i:v instanceof java.sql.Timestamp t?t.toInstant():Instant.parse(v.toString());}
 public record JobView(UUID id,String sourceType,String repositoryName,String remoteUrl,String branch,String status,String currentStep,String errorMessage,UUID resultRepositoryId,Instant createdAt,Instant startedAt,Instant finishedAt){}
}
