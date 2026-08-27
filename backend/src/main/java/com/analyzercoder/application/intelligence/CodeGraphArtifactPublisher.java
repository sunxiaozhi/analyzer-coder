package com.analyzercoder.application.intelligence;

import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以单个数据库事务切换 CodeGraph 发布指针，构建失败时保留原发布产物。 */
@Service
public class CodeGraphArtifactPublisher {
    private final CodeGraphArtifactMapper mapper;

    public CodeGraphArtifactPublisher(CodeGraphArtifactMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void publish(CodeGraphArtifactRow artifact) {
        mapper.retirePublished(artifact.repositoryId());
        mapper.insertPublished(artifact);
    }
}
