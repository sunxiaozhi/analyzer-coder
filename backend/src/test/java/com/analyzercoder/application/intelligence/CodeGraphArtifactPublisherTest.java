package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

class CodeGraphArtifactPublisherTest {
    @Test
    void swapsPublishedArtifactInOneTransactionOnlyAfterBuildProvidesCandidate() throws Exception {
        CodeGraphArtifactMapper mapper = mock(CodeGraphArtifactMapper.class);
        CodeGraphArtifactPublisher publisher = new CodeGraphArtifactPublisher(mapper);
        CodeGraphArtifactRow artifact =
                new CodeGraphArtifactRow(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "1.0",
                        "PUBLISHED",
                        "artifact",
                        10,
                        20);

        publisher.publish(artifact);

        InOrder order = inOrder(mapper);
        order.verify(mapper).retirePublished(artifact.repositoryId());
        order.verify(mapper).insertPublished(artifact);
        assertThat(
                        CodeGraphArtifactPublisher.class
                                .getMethod("publish", CodeGraphArtifactRow.class)
                                .isAnnotationPresent(Transactional.class))
                .isTrue();
    }
}
