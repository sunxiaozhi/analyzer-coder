package com.analyzercoder.application.intelligence;

import com.analyzercoder.infrastructure.persistence.mapper.QaSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 编排问答会话相关应用流程，协调领域对象、权限校验与基础设施端口。 */
@Service
public class QaSessionService {
    private final QaSessionMapper mapper;
    private final ObjectMapper json;

    public QaSessionService(QaSessionMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public Session create(UUID account, List<UUID> repos, String title) {
        if (repos == null || repos.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个仓库");
        }
        UUID id = UUID.randomUUID();
        mapper.insert(
                id,
                account,
                repos.get(0),
                repos.toArray(UUID[]::new),
                title == null || title.isBlank() ? "新会话" : title.trim());
        return get(account, id);
    }

    public List<Session> list(UUID account) {
        return mapper.list(account).stream().map(this::session).toList();
    }

    public Session get(UUID account, UUID id) {
        Map<String, Object> row = mapper.find(id, account);
        if (row == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        return session(row);
    }

    public List<Message> messages(UUID account, UUID id) {
        get(account, id);
        return mapper.messages(id).stream().map(this::message).toList();
    }

    @Transactional
    public void append(
            UUID account, UUID sessionId, String question, IntelligenceService.Answer answer) {
        get(account, sessionId);
        mapper.message(UUID.randomUUID(), sessionId, "user", question, "[]", null);
        try {
            mapper.message(
                    UUID.randomUUID(),
                    sessionId,
                    "assistant",
                    answer.answer(),
                    json.writeValueAsString(answer.citations()),
                    answer.conversationId());
        } catch (Exception e) {
            throw new IllegalStateException("无法保存问答会话", e);
        }
    }

    public Session rename(UUID account, UUID id, String title) {
        if (mapper.rename(id, account, title.trim()) != 1) {
            throw new IllegalArgumentException("会话不存在");
        }
        return get(account, id);
    }

    public void delete(UUID account, UUID id) {
        if (mapper.delete(id, account) != 1) {
            throw new IllegalArgumentException("会话不存在");
        }
    }

    private Session session(Map<String, Object> r) {
        Object ids = val(r, "repository_ids");
        List<UUID> repos =
                ids instanceof java.sql.Array a
                        ? array(a)
                        : ids instanceof UUID[] u ? List.of(u) : List.of(uuid(r, "repo_id"));
        return new Session(
                uuid(r, "id"),
                string(r, "title"),
                repos,
                instant(r, "created_at"),
                instant(r, "updated_at"));
    }

    private static List<UUID> array(java.sql.Array a) {
        try {
            return Arrays.stream((Object[]) a.getArray())
                    .map(v -> v instanceof UUID u ? u : UUID.fromString(v.toString()))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Message message(Map<String, Object> r) {
        return new Message(
                uuid(r, "id"),
                string(r, "role"),
                string(r, "content"),
                string(r, "citations"),
                uuid(r, "conversation_id"),
                instant(r, "created_at"));
    }

    private static Object val(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null ? r.get(k.toUpperCase(Locale.ROOT)) : v;
    }

    private static String string(Map<String, Object> r, String k) {
        Object v = val(r, k);
        return v == null ? null : v.toString();
    }

    private static UUID uuid(Map<String, Object> r, String k) {
        Object v = val(r, k);
        return v == null ? null : v instanceof UUID u ? u : UUID.fromString(v.toString());
    }

    private static Instant instant(Map<String, Object> r, String k) {
        Object v = val(r, k);
        return v == null
                ? null
                : v instanceof Instant i
                        ? i
                        : v instanceof java.sql.Timestamp t
                                ? t.toInstant()
                                : Instant.parse(v.toString());
    }

    public record Session(
            UUID id,
            String title,
            List<UUID> repositoryIds,
            Instant createdAt,
            Instant updatedAt) {}

    public record Message(
            UUID id,
            String role,
            String content,
            String citations,
            UUID conversationId,
            Instant createdAt) {}
}
