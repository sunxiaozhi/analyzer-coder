package com.analyzercoder.application.llm;

import com.analyzercoder.infrastructure.persistence.mapper.LlmSettingsMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Keep model health updates when the surrounding question transaction rolls back. */
@Service
public class LlmRuntimeStateService {
    private final LlmSettingsMapper mapper;

    public LlmRuntimeStateService(LlmSettingsMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(UUID configId) {
        mapper.recordRuntimeSuccess(configId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(UUID configId, String code, int threshold) {
        mapper.recordRuntimeFailure(configId, code, threshold);
    }
}
