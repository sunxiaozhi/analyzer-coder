package com.analyzercoder.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.analyzercoder.CodebaseKnowledgeApplication;
import com.analyzercoder.application.repository.RepositorySourceImportService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.RepositorySnapshotPort;
import com.analyzercoder.infrastructure.persistence.mapper.*;
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

@EnabledIfEnvironmentVariable(named="APP_RUN_POSTGRES_IT",matches="true")
@SpringBootTest(classes=CodebaseKnowledgeApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="spring.main.lazy-initialization=false")
class PostgresMyBatisContextIT {
    @Autowired AuthMapper auth;
    @Autowired RepositoryMapper repositories;
    @Autowired RepositoryGovernanceMapper governance;
    @Autowired IndexJobMapper tasks;
    @Autowired CaptchaMapper captcha;
    @Autowired IntelligenceMapper intelligence;
    @Autowired CodeChunkMapper chunks;
    @Autowired KnowledgeHistoryMapper history;
    @Autowired RepositorySourceImportService imports;
    @Autowired RepositorySnapshotPort managedFiles;

    @Test void loadsFlywaySchemaAndExecutesRepresentativeMapperSql(){
        assertDoesNotThrow(()->{
            auth.accountCount();auth.listAccounts();repositories.findAll();governance.findEnabledAccounts();
            tasks.findAll();captcha.failureCount("__mapper_smoke__");intelligence.settings();intelligence.backups();
            var visible=repositories.findAll();if(!visible.isEmpty()){UUID id=visible.get(0).id();chunks.count(id,null);intelligence.cards(id,true);history.findHistory(id,UUID.randomUUID());}
        });
    }

    @Test @Transactional
    void zipImportPublishesWorktreeInsideUnifiedRepositoryDirectory() throws Exception {
        var accounts=auth.listAccounts();assertFalse(accounts.isEmpty(),"integration database needs an account owner");
        MockMultipartFile upload=new MockMultipartFile("file","sample.zip","application/zip",sampleZip());
        CodeRepository created=null;
        try {
            created=imports.importZip("mapper-smoke-"+UUID.randomUUID(),upload,accounts.get(0).id());
            assertTrue(Files.isDirectory(created.path()));
            assertEquals("worktree",created.path().getFileName().toString());
            assertEquals(created.id().value().toString(),created.path().getParent().getFileName().toString());
            assertTrue(Files.isRegularFile(created.path().resolve("README.md")));
        } finally {
            if(created!=null)managedFiles.deleteRepository(created.id());
        }
    }

    private static byte[] sampleZip() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            zip.putNextEntry(new ZipEntry("README.md"));zip.write("managed import smoke test".getBytes());zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}