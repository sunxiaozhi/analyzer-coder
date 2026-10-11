package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a durable knowledge container without acquiring or scanning any code. */
@Service
public class KnowledgeProjectService {
    private final CodeRepositoryStore repositories;
    private final JdbcTemplate db;
    private final Path dataRoot;

    public KnowledgeProjectService(
            CodeRepositoryStore repositories,
            JdbcTemplate db,
            @Value("${app.repository.managed-data-root:${java.io.tmpdir}/analyzer-coder}")
                    String dataRoot) {
        this.repositories = repositories;
        this.db = db;
        this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
    }

    @Transactional
    public CodeRepository create(AuthenticatedAccount owner, String name, String description) {
        String cleanedName = name == null ? "" : name.trim();
        String cleanedDescription = description == null ? "" : description.trim();
        if (cleanedName.isEmpty()
                || cleanedName.length() > 100
                || cleanedDescription.length() > 500)
            throw new IllegalArgumentException("项目名称为 1–100 个字符，说明最多 500 个字符");
        if (repositories.existsByNormalizedName(owner.id(), cleanedName))
            throw new ApiSecurityException(409, "REPOSITORY_NAME_CONFLICT", "该所有者名下已存在同名项目");
        var id = CodeRepositoryId.newId();
        Path storage = dataRoot.resolve("repositories").resolve(id.value().toString());
        Instant now = Instant.now();
        var project =
                new CodeRepository(
                        id,
                        cleanedName,
                        storage,
                        RepositorySourceType.KNOWLEDGE,
                        "WORKSPACE",
                        null,
                        null,
                        false,
                        null,
                        null,
                        storage.resolve(".codegraph"),
                        null,
                        null,
                        now,
                        now);
        repositories.saveOwned(project, owner.id());
        db.update(
                "UPDATE repositories SET description=? WHERE id=?", cleanedDescription, id.value());
        // The existing repository trigger creates the stable WORKSPACE identity.
        db.update(
                "UPDATE repository_branches SET preparation_status='READY' WHERE repo_id=?",
                id.value());
        return project;
    }
}
