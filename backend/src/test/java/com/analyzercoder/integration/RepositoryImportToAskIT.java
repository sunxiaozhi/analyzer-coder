package com.analyzercoder.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.CodebaseKnowledgeApplication;
import com.analyzercoder.application.branch.BranchCodeOperationsService;
import com.analyzercoder.application.branch.RepositoryBranchService;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.repository.RepositorySourceImportService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.infrastructure.persistence.mapper.AuthMapper;
import com.analyzercoder.infrastructure.repository.GitBranchContentVersionFactory;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthenticatedAccount;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
        properties = "app.workers.enabled=false")
class RepositoryImportToAskIT {
    @Autowired RepositorySourceImportService imports;
    @Autowired RepositoryBranchService branches;
    @Autowired BranchCodeOperationsService operations;
    @Autowired IntelligenceService intelligence;
    @Autowired AuthMapper auth;
    @Autowired GitBranchContentVersionFactory managedFiles;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.analyzercoder.application.indexing.VectorIndexQueryService vectors;

    @Test
    @Transactional
    void importsZipIndexesCurrentContentVersionAndAnswersFromPersistedEvidence() throws Exception {
        jdbc.update(
                "UPDATE vector_model_activation SET active_config_id='00000000-0000-0000-0000-000000000064' WHERE singleton_id=1");

        var owner = auth.listAccounts().get(0);
        var actor =
                new AuthenticatedAccount(
                        owner.id(),
                        owner.username(),
                        owner.displayName(),
                        AccountRole.valueOf(owner.accountRole()),
                        false,
                        null);
        MockMultipartFile upload =
                new MockMultipartFile("file", "e2e.zip", "application/zip", repositoryZip());
        CodeRepository repository = null;
        try {
            repository =
                    imports.importZip("import-to-ask-" + UUID.randomUUID(), upload, owner.id());
            var workspace = branches.list(actor, repository.id().value()).get(0);
            var context =
                    operations.executeSync(
                            actor,
                            repository.id().value(),
                            workspace.id(),
                            null,
                            (stage, detail) -> {},
                            () -> {});
            operations.indexContent(actor, context, () -> {});
            intelligence.prepareBranchEmbeddings(
                    repository.id().value(), context.contentVersion(), () -> {});
            context = branches.resolve(actor, repository.id().value(), workspace.id(), null);
            assertThat(operations.status(actor, context).contentReady()).isTrue();

            IntelligenceService.Answer answer =
                    intelligence.ask(
                            repository.id().value(),
                            owner.id(),
                            "OrderCheckoutWorkflow 在哪里定义？",
                            UUID.randomUUID(),
                            null,
                            null,
                            context);

            assertThat(answer.contentVersion()).isEqualTo(context.contentVersion());
            assertThat(answer.evidenceStatus()).isEqualTo("DEGRADED");
            assertThat(answer.fallbackReason()).isEqualTo("LOCAL_EVIDENCE_MODE");
            assertThat(answer.citations()).isNotEmpty();
            assertThat(answer.citations().get(0).filePath())
                    .isEqualTo("src/OrderCheckoutWorkflow.java");
            assertThat(answer.retrieval().enabledChannels()).contains("CODE_KEYWORD");
            assertThat(answer.retrieval().contentVersion()).isEqualTo(context.contentVersion());
            assertThat(
                            intelligence
                                    .historyDetail(
                                            repository.id().value(),
                                            owner.id(),
                                            answer.threadId(),
                                            context)
                                    .turns())
                    .hasSize(1);

            // Switching models must invalidate coverage before any user query is made.
            var beforeSwitch = vectors.summary(context);
            assertThat(beforeSwitch.missingChunks()).isZero();
            assertThat(beforeSwitch.totalChunks()).isPositive();
            UUID nextModel = UUID.randomUUID();
            String nextModelName = "integration-hash-" + nextModel;
            jdbc.update(
                    "INSERT INTO vector_model_configs(id,name,provider_type,model,dimension) VALUES(?,?,'LOCAL_HASH',?,64)",
                    nextModel,
                    "integration-model",
                    nextModelName);
            jdbc.update(
                    "UPDATE vector_model_activation SET active_config_id=? WHERE singleton_id=1",
                    nextModel);
            var afterSwitch = vectors.summary(context);
            assertThat(afterSwitch.vectorModel()).isEqualTo(nextModelName);
            assertThat(afterSwitch.missingChunks()).isEqualTo(beforeSwitch.totalChunks());
            assertThat(vectors.chunks(context, null, "MISSING", null, 1, 15).items())
                    .hasSize((int) beforeSwitch.totalChunks());
            assertThat(
                            intelligence
                                    .hybridSearchDetailed(
                                            repository.id().value(),
                                            "OrderCheckoutWorkflow",
                                            10,
                                            context)
                                    .hits())
                    .isNotEmpty();
            assertThat(vectors.summary(context).missingChunks())
                    .isEqualTo(beforeSwitch.totalChunks());
            intelligence.prepareBranchEmbeddings(
                    repository.id().value(), context.contentVersion(), () -> {});
            assertThat(vectors.summary(context).missingChunks()).isZero();
        } finally {
            if (repository != null) managedFiles.deleteRepository(repository.id());
        }
    }

    private static byte[] repositoryZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("README.md"));
            zip.write(
                    ("# Checkout demo\n\n"
                                    + "The checkout workflow validates an order before payment.\n")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("src/OrderCheckoutWorkflow.java"));
            zip.write(
                    ("public class OrderCheckoutWorkflow {\n"
                                    + "  public boolean checkout(int quantity) { return quantity > 0; }\n"
                                    + "}\n")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
