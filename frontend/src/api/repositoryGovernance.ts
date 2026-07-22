import { request } from './http';

export type RepositoryPermission = 'READ' | 'MAINTAIN' | 'MANAGE';

export interface RepositoryMember {
  accountId: string;
  username: string;
  displayName: string;
  accountRole: 'SUPER_ADMIN' | 'NORMAL';
  enabled: boolean;
  relationship: 'OWNER' | RepositoryPermission;
  permissionLevel: RepositoryPermission | null;
}

export interface GovernanceCandidate {
  id: string;
  username: string;
  displayName: string;
  enabled: boolean;
}

export const repositoryGovernanceApi = {
  members: (repositoryId: string) =>
    request<RepositoryMember[]>(`/api/repositories/${repositoryId}/governance/members`),
  candidates: (repositoryId: string) =>
    request<GovernanceCandidate[]>(`/api/repositories/${repositoryId}/governance/candidates`),
  grant: (repositoryId: string, accountId: string, permission: RepositoryPermission, expectedOwnershipVersion: number) =>
    request<{ ownershipVersion: number }>(`/api/repositories/${repositoryId}/governance/members/${accountId}`, {
      method: 'PUT',
      body: JSON.stringify({ permission, expectedOwnershipVersion }),
    }),
  revoke: (repositoryId: string, accountId: string, expectedOwnershipVersion: number) =>
    request<{ ownershipVersion: number }>(`/api/repositories/${repositoryId}/governance/members/${accountId}?expectedOwnershipVersion=${expectedOwnershipVersion}`, {
      method: 'DELETE',
    }),
  transfer: (repositoryId: string, input: { newOwnerAccountId: string; newName?: string; previousOwnerPermission: RepositoryPermission | null; expectedOwnershipVersion: number }) =>
    request<{ ownershipVersion: number }>(`/api/repositories/${repositoryId}/governance/transfer`, {
      method: 'POST',
      body: JSON.stringify(input),
    }),
};
