package com.analyzercoder.application.repository;

import com.analyzercoder.infrastructure.persistence.mapper.RepositoryImportJobMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 使用独立短事务持久化远程仓库导入任务状态，避免网络操作占用数据库事务。 */
@Service
public class RepositoryImportJobStateService {
    private final RepositoryImportJobMapper mapper;
    private final RepositoryProjectDraftService drafts;

    public RepositoryImportJobStateService(
            RepositoryImportJobMapper mapper, RepositoryProjectDraftService drafts) {
        this.mapper = mapper;
        this.drafts = drafts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> claim() {
        return mapper.claim();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void step(UUID id, String step) {
        mapper.step(id, step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancel(UUID id) {
        mapper.cancel(id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(UUID id, UUID projectDraftId, UUID repositoryId) {
        mapper.succeed(id, repositoryId);
        drafts.complete(projectDraftId, repositoryId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID id, String message) {
        mapper.fail(id, message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failDraft(UUID projectDraftId, String message) {
        drafts.fail(projectDraftId, message);
    }
}
