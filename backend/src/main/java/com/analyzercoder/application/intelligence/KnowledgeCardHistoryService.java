package com.analyzercoder.application.intelligence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeCardHistoryService {
    private final JdbcTemplate jdbc;

    public KnowledgeCardHistoryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Revision> history(UUID repoId, UUID cardId) {
        return jdbc.query("""
            SELECT card_id,revision,repo_id,title,card_type,content,tags,status,changed_by,changed_at
            FROM knowledge_card_revisions WHERE repo_id=? AND card_id=? ORDER BY revision DESC
            """, KnowledgeCardHistoryService::map, repoId, cardId);
    }

    @Transactional
    public IntelligenceService.KnowledgeCard restore(UUID repoId, UUID cardId, int revision, UUID actor) {
        Revision source = jdbc.query("""
            SELECT card_id,revision,repo_id,title,card_type,content,tags,status,changed_by,changed_at
            FROM knowledge_card_revisions WHERE repo_id=? AND card_id=? AND revision=?
            """, KnowledgeCardHistoryService::map, repoId, cardId, revision).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("知识卡片历史修订不存在"));
        int changed = jdbc.update("""
            UPDATE knowledge_cards SET title=?,card_type=?,content=?,tags=?,status='DRAFT',
              revision=revision+1,updated_by=?,updated_at=CURRENT_TIMESTAMP
            WHERE repo_id=? AND id=?
            """, source.title(), source.cardType(), source.content(), source.tags().toArray(String[]::new), actor, repoId, cardId);
        if (changed == 0) throw new IllegalArgumentException("知识卡片不存在");
        return jdbc.query("""
            SELECT id,repo_id,title,card_type,content,tags,status,revision,created_at,updated_at
            FROM knowledge_cards WHERE repo_id=? AND id=?
            """, (rs,n) -> new IntelligenceService.KnowledgeCard(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getString(3),rs.getString(4),rs.getString(5),array(rs,6),rs.getString(7),rs.getInt(8),
                rs.getTimestamp(9).toInstant(),rs.getTimestamp(10).toInstant()), repoId, cardId).stream().findFirst().orElseThrow();
    }

    private static Revision map(ResultSet rs, int row) throws SQLException {
        return new Revision(rs.getObject(1,UUID.class),rs.getInt(2),rs.getObject(3,UUID.class),rs.getString(4),
            rs.getString(5),rs.getString(6),array(rs,7),rs.getString(8),rs.getObject(9,UUID.class),rs.getTimestamp(10).toInstant());
    }

    private static List<String> array(ResultSet rs, int column) throws SQLException {
        return Arrays.asList((String[]) rs.getArray(column).getArray());
    }

    public record Revision(UUID cardId,int revision,UUID repositoryId,String title,String cardType,String content,
        List<String> tags,String status,UUID changedBy,Instant changedAt) {}
}
