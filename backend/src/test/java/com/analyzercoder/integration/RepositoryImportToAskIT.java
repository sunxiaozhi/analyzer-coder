package com.analyzercoder.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.analyzercoder.CodebaseKnowledgeApplication;
import com.analyzercoder.application.indexing.IndexJobProcessor;
import com.analyzercoder.application.indexing.IndexJobUseCase;
import com.analyzercoder.application.indexing.StartIndexCommand;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.llm.LlmSettingsService;
import com.analyzercoder.application.repository.RepositorySourceImportService;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.RepositorySnapshotPort;
import com.analyzercoder.infrastructure.persistence.mapper.AuthMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfEnvironmentVariable(named = "APP_RUN_POSTGRES_IT", matches = "true")
@SpringBootTest(
        classes = CodebaseKnowledgeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RepositoryImportToAskIT {
    @Autowired RepositorySourceImportService imports;
    @Autowired IndexJobUseCase jobs;
    @Autowired IndexJobProcessor processor;
    @Autowired IntelligenceService intelligence;
    @Autowired AuthMapper auth;
    @Autowired RepositorySnapshotPort managedFiles;

    @MockitoBean LlmSettingsService llm;

    @Test
    @Transactional
    void importsZipIndexesCurrentSnapshotAndAnswersFromPersistedEvidence() throws Exception {
        when(llm.activeVectorModelName()).thenReturn("local-hash-64");
        when(llm.activeVectorModelDimension()).thenReturn(64);
        when(llm.activeRetrievalCapability()).thenReturn("CHARACTER_HASH");
        when(llm.vectorize(anyString()))
                .thenReturn(
                        new LlmSettingsService.VectorEmbedding(
                                "local-hash-64", 64, null, "CHARACTER_HASH"));
        when(llm.generate(org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(Optional.empty());

        var owner = auth.listAccounts().get(0);
        MockMultipartFile upload =
                new MockMultipartFile(
                        "file", "e2e.zip", "application/zip", repositoryZip());
        CodeRepository repository = null;
        try {
            repository =
                    imports.importZip(
                            "import-to-ask-" + UUID.randomUUID(), upload, owner.id());
            var queued =
                    jobs.start(
                            new StartIndexCommand(repository.id(), IndexJobType.FULL));

            assertThat(processor.processNextQueuedJob()).isTrue();
            assertThat(jobs.get(queued.id()).status()).isEqualTo(IndexJobStatus.SUCCEEDED);

            IntelligenceService.Answer answer =
                    intelligence.ask(
                            repository.id().value(),
                            owner.id(),
                            "OrderCheckoutWorkflow 在哪里定义？",
                            UUID.randomUUID(),
                            null,
                            UUID.randomUUID());

            assertThat(answer.snapshotId()).isEqualTo(repository.currentSnapshotId().value());
            assertThat(answer.evidenceStatus()).isEqualTo("DEGRADED");
            assertThat(answer.citations()).isNotEmpty();
            assertThat(answer.citations().get(0).filePath()).isEqualTo("README.md");
            assertThat(answer.retrieval().enabledChannels()).contains("CODE_KEYWORD");
            assertThat(answer.retrieval().snapshotId())
                    .isEqualTo(repository.currentSnapshotId().value());
        } finally {
            if (repository != null) managedFiles.deleteRepository(repository.id());
        }
    }

    private static byte[] repositoryZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("README.md"));
            zip.write(
                    ("# OrderCheckoutWorkflow\n\n"
                                    + "The checkout workflow validates an order before payment.\n")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
