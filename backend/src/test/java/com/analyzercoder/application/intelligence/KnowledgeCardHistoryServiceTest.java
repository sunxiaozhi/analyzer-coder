package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.infrastructure.persistence.mapper.KnowledgeHistoryMapper;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeCardRow;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeRevisionRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeCardHistoryServiceTest {
    @Test
    void restoresAttachmentAndCodeReferencesIntoNewRevision() {
        var mapper = mock(KnowledgeHistoryMapper.class);
        var attachments = mock(KnowledgeAttachmentService.class);
        var intelligence = mock(IntelligenceService.class);
        var service =
                new KnowledgeCardHistoryService(
                        mapper, attachments, intelligence, new ObjectMapper());
        UUID repo = UUID.randomUUID(), cardId = UUID.randomUUID(), actor = UUID.randomUUID();
        var source = mock(KnowledgeRevisionRow.class);
        var restored = mock(KnowledgeCardRow.class);
        var attachment = mock(KnowledgeAttachmentService.Attachment.class);
        var expected = mock(IntelligenceService.KnowledgeCard.class);
        var attachmentId = UUID.randomUUID();
        when(mapper.findRevision(repo, cardId, 2)).thenReturn(source);
        when(mapper.restore(repo, cardId, source, actor)).thenReturn(1);
        when(mapper.findCard(repo, cardId)).thenReturn(restored);
        when(restored.revision()).thenReturn(7);
        when(attachment.id()).thenReturn(attachmentId);
        when(attachments.list(repo, cardId, 2)).thenReturn(List.of(attachment));
        when(intelligence.card(repo, cardId)).thenReturn(expected);

        assertThat(service.restore(repo, cardId, 2, actor)).isSameAs(expected);
        verify(attachments).attach(repo, cardId, 7, List.of(attachmentId));
        verify(mapper).copyCodeReferences(repo, cardId, 2, 7);
    }
}
