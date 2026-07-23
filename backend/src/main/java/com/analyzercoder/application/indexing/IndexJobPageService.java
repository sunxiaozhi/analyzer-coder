package com.analyzercoder.application.indexing;

import com.analyzercoder.application.common.PageResult;
import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.infrastructure.indexing.PostgresIndexJobStore;
import com.analyzercoder.infrastructure.persistence.mapper.IndexJobMapper;
import com.analyzercoder.security.AuthenticatedAccount;
import com.github.pagehelper.PageHelper;
import org.springframework.stereotype.Service;

@Service
public class IndexJobPageService {
    private final IndexJobMapper mapper;

    public IndexJobPageService(IndexJobMapper mapper) {
        this.mapper = mapper;
    }

    public PageResult<IndexJob> page(AuthenticatedAccount account, int pageNum, int pageSize) {
        PageResult.validate(pageNum, pageSize);
        PageHelper.startPage(pageNum, pageSize);
        return PageResult.fromPage(mapper.findVisiblePage(account.id(), account.isSuperAdmin()))
            .map(PostgresIndexJobStore::toDomain);
    }
}
