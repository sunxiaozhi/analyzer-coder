package com.analyzercoder.application.intelligence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntelligenceService {
    private static final int DIM = 64;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public IntelligenceService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional
    public List<SearchHit> hybridSearch(UUID repoId, String query, int limit) {
        ensureEmbeddings(repoId);
        return jdbc.query("""
            SELECT c.id,c.snapshot_id,c.file_path,c.symbol_name,c.symbol_kind,c.start_line,c.end_line,c.content,c.content_hash,
              (CASE WHEN POSITION(LOWER(?) IN LOWER(c.file_path))>0 THEN .35 ELSE 0 END
              +CASE WHEN POSITION(LOWER(?) IN LOWER(COALESCE(c.symbol_name,'')))>0 THEN .4 ELSE 0 END
              +CASE WHEN POSITION(LOWER(?) IN LOWER(c.content))>0 THEN .25 ELSE 0 END
              +(1-(e.embedding <=> ?::vector))*.55) score
            FROM code_chunks c JOIN chunk_embeddings e ON e.chunk_id=c.id WHERE c.repo_id=?
            ORDER BY score DESC,c.file_path,c.start_line NULLS FIRST LIMIT ?
            """, (rs, n) -> new SearchHit(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getObject(6,Integer.class),rs.getObject(7,Integer.class),
                rs.getString(8),rs.getString(9),rs.getDouble(10),List.of("KEYWORD","SEMANTIC")),
            query,query,query,vector(query),repoId,Math.max(1,Math.min(limit,100)));
    }

    @Transactional
    public Answer ask(UUID repoId, UUID accountId, String question) {
        List<SearchHit> hits=hybridSearch(repoId,question,6); UUID id=UUID.randomUUID();
        UUID snapshot=hits.isEmpty()?null:hits.get(0).snapshotId();
        String answer;
        if(hits.isEmpty()) answer="当前仓库索引中没有找到足够证据。请先完成内容索引，或换一种更具体的描述。";
        else { StringBuilder b=new StringBuilder("根据当前快照中的代码证据，相关实现主要分布在：");
            for(int i=0;i<Math.min(3,hits.size());i++){SearchHit h=hits.get(i);b.append("\n").append(i+1).append(". ").append(h.filePath());if(h.symbolName()!=null)b.append(" 的 ").append(h.symbolName());b.append("（第 ").append(h.startLine()==null?"?":h.startLine()).append(" 行附近）");}
            answer=b.append("。回答由本地确定性检索生成；涉及行为判断时，请以引用源码为准。").toString(); }
        jdbc.update("INSERT INTO qa_conversations(id,repo_id,account_id,question,answer,snapshot_id) VALUES (?,?,?,?,?,?)",id,repoId,accountId,question,answer,snapshot);
        List<Citation> citations=new ArrayList<>();
        for(int i=0;i<hits.size();i++){SearchHit h=hits.get(i);UUID citationId=UUID.randomUUID();
            jdbc.update("INSERT INTO qa_citations(id,conversation_id,chunk_id,file_path,symbol_name,start_line,end_line,evidence_hash,rank) VALUES (?,?,?,?,?,?,?,?,?)",citationId,id,h.chunkId(),h.filePath(),h.symbolName(),h.startLine(),h.endLine(),h.contentHash(),i+1);
            citations.add(new Citation(citationId,h.chunkId(),h.filePath(),h.symbolName(),h.startLine(),h.endLine(),h.content(),i+1)); }
        return new Answer(id,answer,snapshot,citations,"deterministic-local",Instant.now());
    }

    @Transactional
    public GraphResult graph(UUID repoId,String symbol,int depth,String direction){
        rebuildGraph(repoId);int max=Math.max(1,Math.min(depth,5));
        List<GraphEdge> all=jdbc.query("SELECT source_symbol,target_symbol,relation FROM code_graph_edges WHERE repo_id=? LIMIT 500",
            (rs,n)->new GraphEdge(rs.getString(1),rs.getString(2),rs.getString(3)),repoId);
        Map<String,Integer> distances=new LinkedHashMap<>();distances.put(symbol,0);List<GraphEdge> edges=new ArrayList<>();
        for(int d=0;d<max;d++)for(GraphEdge e:all){Integer from=distances.get(e.source()),to=distances.get(e.target());
            if(from!=null&&from==d&&!"UPSTREAM".equals(direction)){distances.putIfAbsent(e.target(),d+1);edges.add(e);}
            if(to!=null&&to==d&&!"DOWNSTREAM".equals(direction)){distances.putIfAbsent(e.source(),d+1);edges.add(e);}}
        List<GraphNode> nodes=distances.entrySet().stream().map(e->new GraphNode(e.getKey(),e.getValue(),e.getKey().equals(symbol))).toList();
        List<GraphEdge> unique=edges.stream().distinct().toList();
        return new GraphResult(nodes,unique,unique.size()>20?"HIGH":unique.size()>5?"MEDIUM":"LOW",List.of("静态关系不包含运行时反射与动态分派","结果绑定当前已发布快照"));
    }

    public List<KnowledgeCard> cards(UUID repoId,boolean includeDraft){
        String condition=includeDraft?"":" AND status='PUBLISHED'";
        return jdbc.query("SELECT id,repo_id,title,card_type,content,tags,status,revision,created_at,updated_at FROM knowledge_cards WHERE repo_id=?"+condition+" ORDER BY updated_at DESC",
            (rs,n)->new KnowledgeCard(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),Arrays.asList((String[])rs.getArray(6).getArray()),rs.getString(7),rs.getInt(8),rs.getTimestamp(9).toInstant(),rs.getTimestamp(10).toInstant()),repoId);
    }
    @Transactional public KnowledgeCard createCard(UUID repoId,UUID actor,CardInput in){UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_cards(id,repo_id,title,card_type,content,tags,status,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?)",id,repoId,in.title(),in.cardType(),in.content(),in.tags().toArray(String[]::new),in.status(),actor,actor);return findCard(repoId,id);}
    @Transactional public KnowledgeCard updateCard(UUID repoId,UUID id,UUID actor,CardInput in){int changed=jdbc.update("UPDATE knowledge_cards SET title=?,card_type=?,content=?,tags=?,status=?,revision=revision+1,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND repo_id=?",in.title(),in.cardType(),in.content(),in.tags().toArray(String[]::new),in.status(),actor,id,repoId);if(changed==0)throw new IllegalArgumentException("知识卡片不存在");return findCard(repoId,id);}
    private KnowledgeCard findCard(UUID repoId,UUID id){return cards(repoId,true).stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();}

    public Map<String,String> settings(){Map<String,String> out=new LinkedHashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT setting_key,CASE WHEN sensitive THEN '******' ELSE setting_value END AS value FROM system_settings ORDER BY setting_key"))out.put(String.valueOf(row.get("setting_key")),String.valueOf(row.get("value")));return out;}
    @Transactional public Map<String,String> saveSettings(UUID actor,Map<String,String> values){values.forEach((k,v)->jdbc.update("INSERT INTO system_settings(setting_key,setting_value,updated_by) VALUES (?,?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=EXCLUDED.setting_value,updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP",k,v,actor));return settings();}

    public List<BackupView> backups(){return jdbc.query("SELECT id,status,checksum,created_at,restored_at FROM backup_sets ORDER BY created_at DESC",(rs,n)->new BackupView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant(),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant()));}
    @Transactional public BackupView createBackup(UUID actor){Map<String,Object> m=new LinkedHashMap<>();m.put("format",1);m.put("createdAt",Instant.now().toString());m.put("settings",settings());m.put("knowledgeCards",jdbc.queryForList("SELECT * FROM knowledge_cards"));m.put("repositories",jdbc.queryForList("SELECT id,name,source_type,current_snapshot_id FROM repositories"));m.put("accounts",jdbc.queryForList("SELECT id,username,display_name,role,enabled FROM accounts"));
        try{String payload=json.writeValueAsString(m),checksum=sha256(payload);UUID id=UUID.randomUUID();jdbc.update("INSERT INTO backup_sets(id,status,manifest,checksum,created_by) VALUES (?,'READY',?::jsonb,?,?)",id,payload,checksum,actor);return backups().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();}catch(JsonProcessingException e){throw new IllegalStateException("无法生成备份清单",e);}}
    @Transactional public void restoreBackup(UUID id){String payload=jdbc.queryForObject("SELECT manifest::text FROM backup_sets WHERE id=? AND status='READY'",String.class,id);if(payload==null)throw new IllegalArgumentException("备份不存在或不可恢复");String expected=jdbc.queryForObject("SELECT checksum FROM backup_sets WHERE id=?",String.class,id);if(!sha256(payload).equals(expected))throw new IllegalStateException("备份校验失败");jdbc.update("DELETE FROM login_sessions");jdbc.update("UPDATE backup_sets SET restored_at=CURRENT_TIMESTAMP WHERE id=?",id);}

    private void ensureEmbeddings(UUID repoId){for(Map<String,Object> row:jdbc.queryForList("SELECT c.id,c.content,c.content_hash FROM code_chunks c LEFT JOIN chunk_embeddings e ON e.chunk_id=c.id WHERE c.repo_id=? AND (e.chunk_id IS NULL OR e.content_hash<>c.content_hash)",repoId))jdbc.update("INSERT INTO chunk_embeddings(chunk_id,repo_id,model,dimension,embedding,content_hash) VALUES (?,?,'local-hash-64',64,?::vector,?) ON CONFLICT(chunk_id) DO UPDATE SET embedding=EXCLUDED.embedding,content_hash=EXCLUDED.content_hash,created_at=CURRENT_TIMESTAMP",row.get("id"),repoId,vector(String.valueOf(row.get("content"))),row.get("content_hash"));}
    private void rebuildGraph(UUID repoId){jdbc.update("DELETE FROM code_graph_edges WHERE repo_id=?",repoId);List<Map<String,Object>> chunks=jdbc.queryForList("SELECT id,snapshot_id,symbol_name,content FROM code_chunks WHERE repo_id=? AND symbol_name IS NOT NULL",repoId);for(Map<String,Object>s:chunks)for(Map<String,Object>t:chunks){if(s.get("id").equals(t.get("id")))continue;String name=String.valueOf(t.get("symbol_name"));if(!name.isBlank()&&String.valueOf(s.get("content")).contains(name+"("))jdbc.update("INSERT INTO code_graph_edges(id,repo_id,snapshot_id,source_chunk_id,target_chunk_id,source_symbol,target_symbol,relation) VALUES (?,?,?,?,?,?,?,'CALLS') ON CONFLICT DO NOTHING",UUID.randomUUID(),repoId,s.get("snapshot_id"),s.get("id"),t.get("id"),s.get("symbol_name"),name);}}
    private static String vector(String text){float[] out=new float[DIM];String s=text.toLowerCase(Locale.ROOT);for(int i=0;i<s.length();i++){int h=s.substring(i,Math.min(s.length(),i+3)).hashCode();out[Math.floorMod(h,DIM)]+=(h&1)==0?1:-1;}double norm=0;for(float x:out)norm+=x*x;norm=Math.sqrt(norm);StringBuilder b=new StringBuilder("[");for(int i=0;i<DIM;i++){if(i>0)b.append(',');b.append(norm==0?0:out[i]/norm);}return b.append(']').toString();}
    private static String sha256(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}

    public record SearchHit(UUID chunkId,UUID snapshotId,String filePath,String symbolName,String symbolKind,Integer startLine,Integer endLine,String content,String contentHash,double score,List<String> channels){}
    public record Citation(UUID id,UUID chunkId,String filePath,String symbolName,Integer startLine,Integer endLine,String content,int rank){}
    public record Answer(UUID conversationId,String answer,UUID snapshotId,List<Citation> citations,String provider,Instant createdAt){}
    public record GraphNode(String symbol,int depth,boolean focus){} public record GraphEdge(String source,String target,String relation){}
    public record GraphResult(List<GraphNode> nodes,List<GraphEdge> edges,String risk,List<String> limitations){}
    public record CardInput(String title,String cardType,String content,List<String> tags,String status){public CardInput{if(tags==null)tags=List.of();if(status==null)status="DRAFT";}}
    public record KnowledgeCard(UUID id,UUID repositoryId,String title,String cardType,String content,List<String> tags,String status,int revision,Instant createdAt,Instant updatedAt){}
    public record BackupView(UUID id,String status,String checksum,Instant createdAt,Instant restoredAt){}
}
