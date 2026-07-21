package com.analyzercoder.security;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccessControlService {
    private final JdbcTemplate jdbcTemplate;

    public AccessControlService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean canAccess(AuthenticatedAccount account, CodeRepositoryId repositoryId, RepositoryPermission required) {
        if (account.isSuperAdmin()) return true;
        List<String> levels = jdbcTemplate.queryForList(
            "SELECT permission_level FROM repository_permissions WHERE account_id = ? AND repo_id = ?",
            String.class, account.id(), repositoryId.value()
        );
        return !levels.isEmpty() && RepositoryPermission.valueOf(levels.get(0)).includes(required);
    }

    public void require(AuthenticatedAccount account, CodeRepositoryId repositoryId, RepositoryPermission required) {
        if (!canAccess(account, repositoryId, required)) {
            throw new ApiSecurityException(403, "FORBIDDEN", "无权限访问该仓库");
        }
    }

    public List<UUID> visibleRepositoryIds(AuthenticatedAccount account) {
        if (account.isSuperAdmin()) {
            return jdbcTemplate.queryForList("SELECT id FROM repositories", UUID.class);
        }
        return jdbcTemplate.queryForList(
            "SELECT repo_id FROM repository_permissions WHERE account_id = ?", UUID.class, account.id()
        );
    }
}
