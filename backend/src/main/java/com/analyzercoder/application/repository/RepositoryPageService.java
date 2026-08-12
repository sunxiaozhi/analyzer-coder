package com.analyzercoder.application.repository;

import com.analyzercoder.application.common.PageResult;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.infrastructure.repository.PostgresCodeRepositoryStore;
import com.analyzercoder.security.AuthenticatedAccount;
import com.github.pagehelper.PageHelper;
import org.springframework.stereotype.Service;

/** 编排代码仓库相关应用流程，协调领域对象、权限校验与基础设施端口。 */
@Service
public class RepositoryPageService {
    private final RepositoryMapper mapper;

    public RepositoryPageService(RepositoryMapper mapper) {
        this.mapper = mapper;
    }

    public PageResult<CodeRepository> page(
            AuthenticatedAccount account, String query, int pageNum, int pageSize) {
        PageResult.validate(pageNum, pageSize);
        String normalizedQuery = query == null || query.isBlank() ? null : query.trim();
        PageHelper.startPage(pageNum, pageSize);
        return PageResult.fromPage(
                        mapper.findVisiblePage(
                                account.id(), account.isSuperAdmin(), normalizedQuery))
                .map(PostgresCodeRepositoryStore::toDomain);
    }
}
