package com.analyzercoder.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.analyzercoder.application.branch.RepositoryBranchService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class KnowledgeProjectOperationInterceptorTest {
    private final UUID repositoryId = UUID.randomUUID();
    private final RepositoryBranchService branches = mock(RepositoryBranchService.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final KnowledgeProjectOperationInterceptor interceptor =
            new KnowledgeProjectOperationInterceptor(branches, access);

    @Test
    void rejectsDirectCodeRequestsBeforeHandlersCanEnqueueOrBindCredentials() {
        doThrow(new ApiSecurityException(409, "KNOWLEDGE_PROJECT_NO_CODE", "纯知识库项目不提供代码操作"))
                .when(branches)
                .requireCodeProject(repositoryId);
        for (String path :
                new String[] {
                    "/index",
                    "/rescan",
                    "/codegraph/build",
                    "/credential",
                    "/branches/" + UUID.randomUUID() + "/prepare",
                    "/knowledge/markdown-sources/generate"
                }) {
            var request = request("POST", path);
            assertThatThrownBy(
                            () ->
                                    interceptor.preHandle(
                                            request, new MockHttpServletResponse(), null))
                    .isInstanceOf(ApiSecurityException.class)
                    .hasMessageContaining("纯知识库");
        }
    }

    @Test
    void keepsKnowledgeAndContextRequestsAvailable() {
        for (String[] route :
                new String[][] {
                    {"POST", "/contexts"},
                    {"GET", "/branches"},
                    {"POST", "/knowledge"},
                    {"POST", "/knowledge/" + UUID.randomUUID() + "/publish"},
                    {"POST", "/ask"},
                    {"GET", "/evidence-search"}
                }) {
            assertThat(
                            interceptor.preHandle(
                                    request(route[0], route[1]),
                                    new MockHttpServletResponse(),
                                    null))
                    .isTrue();
        }
        verifyNoInteractions(branches, access);
    }

    private MockHttpServletRequest request(String method, String path) {
        var request =
                new MockHttpServletRequest(method, "/api/repositories/" + repositoryId + path);
        request.setAttribute(
                SecurityContext.TOKEN_ACCOUNT_ATTRIBUTE,
                new AuthenticatedAccount(
                        UUID.randomUUID(), "test", "Test", AccountRole.NORMAL, false, null));
        return request;
    }
}
