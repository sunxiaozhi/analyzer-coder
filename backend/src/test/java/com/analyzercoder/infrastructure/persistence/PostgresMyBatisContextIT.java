package com.analyzercoder.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.analyzercoder.CodebaseKnowledgeApplication;
import com.analyzercoder.application.indexing.VectorIndexQueryService;
import com.analyzercoder.application.llm.LlmSettingsService;
import com.analyzercoder.application.repository.RepositorySourceImportService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.RepositorySnapshotPort;
import com.analyzercoder.infrastructure.persistence.mapper.AuthMapper;
import com.analyzercoder.infrastructure.persistence.mapper.CaptchaMapper;
import com.analyzercoder.infrastructure.persistence.mapper.CodeChunkMapper;
import com.analyzercoder.infrastructure.persistence.mapper.IndexJobMapper;
import com.analyzercoder.infrastructure.persistence.mapper.IntelligenceMapper;
import com.analyzercoder.infrastructure.persistence.mapper.KnowledgeHistoryMapper;
import com.analyzercoder.infrastructure.persistence.mapper.LlmSettingsMapper;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryGovernanceMapper;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.infrastructure.persistence.mapper.VectorIndexQueryMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfEnvironmentVariable(named = "APP_RUN_POSTGRES_IT", matches = "true")
@SpringBootTest(
        classes = CodebaseKnowledgeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.lazy-initialization=false")
class PostgresMyBatisContextIT {
    @Autowired AuthMapper auth;
    @Autowired RepositoryMapper repositories;
    @Autowired RepositoryGovernanceMapper governance;
    @Autowired IndexJobMapper tasks;
    @Autowired CaptchaMapper captcha;
    @Autowired IntelligenceMapper intelligence;
    @Autowired LlmSettingsMapper llmSettings;
    @Autowired LlmSettingsService llmService;
    @Autowired CodeChunkMapper chunks;
    @Autowired VectorIndexQueryMapper vectorIndex;
    @Autowired VectorIndexQueryService vectorIndexService;
    @Autowired KnowledgeHistoryMapper history;
    @Autowired RepositorySourceImportService imports;
    @Autowired RepositorySnapshotPort managedFiles;

    @Test
    void loadsFlywaySchemaAndExecutesRepresentativeMapperSql() {
        assertDoesNotThrow(
                () -> {
                    auth.accountCount();
                    auth.listAccounts();
                    repositories.findAll();
                    governance.findEnabledAccounts();
                    tasks.findAll();
                    captcha.failureCount("__mapper_smoke__");
                    intelligence.settings();
                    llmSettings.latestConfig();
                    llmSettings.vectorModels();
                    llmSettings.activeVectorModel();
                    var visible = repositories.findAll();
                    if (!visible.isEmpty()) {
                        UUID id = visible.get(0).id();
                        chunks.count(id, null);
                        intelligence.cards(id, true);
                        history.findHistory(id, UUID.randomUUID());
                        vectorIndex.summary(id);
                        vectorIndex.chunks(id, null, null, null);
                        vectorIndex.knowledge(id, null, null);
                        vectorIndexService.summary(id);
                        vectorIndexService.chunks(id, null, null, null, 1, 15);
                        vectorIndexService.knowledge(id, null, null, 1, 15);
                    }
                });
    }

    @Test
    @Transactional
    void zipImportPublishesWorktreeInsideUnifiedRepositoryDirectory() throws Exception {
        var accounts = auth.listAccounts();
        assertFalse(accounts.isEmpty(), "integration database needs an account owner");
        MockMultipartFile upload =
                new MockMultipartFile("file", "sample.zip", "application/zip", sampleZip());
        CodeRepository created = null;
        try {
            created =
                    imports.importZip(
                            "mapper-smoke-" + UUID.randomUUID(), upload, accounts.get(0).id());
            assertTrue(Files.isDirectory(created.path()));
            assertEquals("worktree", created.path().getFileName().toString());
            assertEquals(
                    created.id().value().toString(),
                    created.path().getParent().getFileName().toString());
            assertTrue(Files.isRegularFile(created.path().resolve("README.md")));
        } finally {
            if (created != null) {
                managedFiles.deleteRepository(created.id());
            }
        }
    }

    @Test
    @Transactional
    void savesVersionedLlmConfigurationWithoutPlaintextSecret() {
        var accounts = auth.listAccounts();
        assertFalse(accounts.isEmpty(), "integration database needs an admin");
        var saved =
                llmService.save(
                        accounts.get(0).id(),
                        new LlmSettingsService.ProviderInput(
                                "integration-provider",
                                "OPENAI_COMPATIBLE",
                                "https://llm.example.com/v1",
                                "test-model",
                                5000,
                                60000,
                                1024,
                                0.2,
                                true,
                                "CLEAR",
                                null));
        assertNotNull(saved.id());
        assertEquals("UNTESTED", saved.availability());
        assertFalse(saved.secretConfigured());
        assertEquals(64, saved.fingerprint().length());
    }

    private static byte[] sampleZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("README.md"));
            zip.write("managed import smoke test".getBytes());
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
