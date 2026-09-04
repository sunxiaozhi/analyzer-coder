package com.analyzercoder.application.repository;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.project.EngineeringProjectService;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryGovernanceMapper;
import com.analyzercoder.infrastructure.persistence.model.RepositoryMemberRow;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RepositoryGovernanceServiceTest {
    @Mock private RepositoryGovernanceMapper mapper;
    @Mock private AccessControlService access;
    @Mock private IndexJobStore indexJobs;
    @Mock private EngineeringProjectService engineeringProjects;
    @InjectMocks private RepositoryGovernanceService service;

    @Test
    void maintainersCanListRepositoryMembersForKnowledgeOwnershipSelection() {
        UUID repositoryId = UUID.randomUUID();
        AuthenticatedAccount actor =
                new AuthenticatedAccount(
                        UUID.randomUUID(),
                        "maintainer",
                        "维护者",
                        AccountRole.NORMAL,
                        false,
                        null);
        List<RepositoryMemberRow> members = List.of();
        when(mapper.findMembers(repositoryId)).thenReturn(members);

        service.members(actor, repositoryId);

        verify(access)
                .require(actor, CodeRepositoryId.of(repositoryId), RepositoryPermission.MAINTAIN);
        verify(mapper).findMembers(repositoryId);
    }
}
