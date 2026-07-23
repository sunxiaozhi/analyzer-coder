package com.analyzercoder.security;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryAccessMapper;
import com.analyzercoder.infrastructure.persistence.model.RepositoryAccessRow;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccessControlService {
    private final RepositoryAccessMapper mapper;
    public AccessControlService(RepositoryAccessMapper mapper){this.mapper=mapper;}

    public boolean canAccess(AuthenticatedAccount account,CodeRepositoryId repositoryId,RepositoryPermission required){
        if(account.isSuperAdmin())return true;
        RepositoryAccessRow access=mapper.findAccess(account.id(),repositoryId.value());
        if(access==null)return false;
        if(account.id().equals(access.ownerAccountId()))return true;
        return access.permissionLevel()!=null&&RepositoryPermission.valueOf(access.permissionLevel()).includes(required);
    }
    public void require(AuthenticatedAccount account,CodeRepositoryId repositoryId,RepositoryPermission required){if(!canAccess(account,repositoryId,required))throw new ApiSecurityException(403,"FORBIDDEN","无权限访问该仓库");}
    public void requireOwner(AuthenticatedAccount account,CodeRepositoryId repositoryId){
        if(account.isSuperAdmin())return;
        RepositoryAccessRow access=mapper.findAccess(account.id(),repositoryId.value());
        if(access==null||!account.id().equals(access.ownerAccountId()))throw new ApiSecurityException(403,"OWNER_REQUIRED","只有仓库所有者或超级管理员可执行此操作");
    }
    public List<UUID> visibleRepositoryIds(AuthenticatedAccount account){return account.isSuperAdmin()?mapper.findVisibleRepositoryIdsForAdmin():mapper.findVisibleRepositoryIds(account.id());}
    public RepositoryAccess describe(AuthenticatedAccount account,CodeRepositoryId repositoryId){
        RepositoryAccessRow row=account.isSuperAdmin()?mapper.findMetadata(repositoryId.value()):mapper.findAccess(account.id(),repositoryId.value());
        if(row==null)throw new ApiSecurityException(403,"FORBIDDEN","无权限访问该仓库");
        boolean admin=account.isSuperAdmin(),owner=account.id().equals(row.ownerAccountId());
        RepositoryPermission permission=owner||admin?RepositoryPermission.MANAGE:RepositoryPermission.valueOf(row.permissionLevel());
        String relationship=admin?"SUPER_ADMIN":owner?"OWNER":permission.name();
        boolean maintain=permission.includes(RepositoryPermission.MAINTAIN),manage=permission.includes(RepositoryPermission.MANAGE);
        return new RepositoryAccess(row.ownerAccountId(),row.ownerDisplayName(),relationship,row.ownershipVersion(),row.repositoryStatus(),
            new RepositoryAccess.Capabilities(true,manage,maintain,maintain,maintain,manage,owner||admin,owner||admin,owner||admin,owner||admin));
    }
}
