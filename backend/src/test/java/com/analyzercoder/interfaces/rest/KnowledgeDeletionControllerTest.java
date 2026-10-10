package com.analyzercoder.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.analyzercoder.application.branch.BranchReadContext;
import com.analyzercoder.application.knowledge.KnowledgeDeletionService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.AuthenticatedSession;
import com.analyzercoder.security.SecurityContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KnowledgeDeletionControllerTest {
    final KnowledgeDeletionService service = mock(KnowledgeDeletionService.class);
    final BranchRequestContext contexts = mock(BranchRequestContext.class);
    final UUID repo = UUID.randomUUID(), card = UUID.randomUUID();
    final AuthenticatedAccount actor =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "manager", "Manager", AccountRole.NORMAL, false, null);
    final AuthenticatedSession session = new AuthenticatedSession("token", "csrf", actor);
    final BranchReadContext context =
            new BranchReadContext(
                    UUID.randomUUID(),
                    repo,
                    UUID.randomUUID(),
                    "main",
                    UUID.randomUUID(),
                    "commit",
                    Path.of("."),
                    Instant.now());
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.standaloneSetup(new KnowledgeDeletionController(service, contexts))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
        when(contexts.resolve(any(), eq(repo))).thenReturn(context);
    }

    String url() {
        return "/api/repositories/" + repo + "/knowledge/" + card;
    }

    @Test
    void deletesSelectedRevisionAndReturnsNoContent() throws Exception {
        mvc.perform(
                        delete(url())
                                .param("expectedRevision", "2")
                                .header("X-Branch-Context", context.contextId())
                                .requestAttr(SecurityContext.SESSION_ATTRIBUTE, session))
                .andExpect(status().isNoContent());
        verify(service).delete(actor, context, card, 2);
    }

    @Test
    void rejectsMissingRevisionBeforeCallingDeletion() throws Exception {
        mvc.perform(delete(url()).requestAttr(SecurityContext.SESSION_ATTRIBUTE, session))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"0", "-1", "invalid", "9999999999999999"})
    void rejectsInvalidRevision(String revision) throws Exception {
        mvc.perform(
                        delete(url())
                                .param("expectedRevision", revision)
                                .requestAttr(SecurityContext.SESSION_ATTRIBUTE, session))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(delete(url()).param("expectedRevision", "2"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void surfacesDraftOnlyConflicts() throws Exception {
        doThrow(new ApiSecurityException(409, "KNOWLEDGE_DELETE_REQUIRES_DRAFT", "仅草稿可以删除"))
                .when(service)
                .delete(actor, context, card, 2);
        mvc.perform(
                        delete(url())
                                .param("expectedRevision", "2")
                                .requestAttr(SecurityContext.SESSION_ATTRIBUTE, session))
                .andExpect(status().isConflict());
    }
}
