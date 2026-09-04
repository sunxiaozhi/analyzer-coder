import { request } from '@/api/http';

export type RepositoryCredentialType = 'GIT_HTTP_TOKEN' | 'GITLAB_PAT';

export interface RepositoryCredential {
  id: string;
  type: RepositoryCredentialType;
  displayName: string;
  serverUrl: string;
  username: string;
  maskedValue: string;
  status: 'ACTIVE' | 'DISABLED' | 'INVALID';
  lastValidatedAt: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface RepositoryCredentialInput {
  type: RepositoryCredentialType;
  displayName: string;
  serverUrl: string;
  username: string;
  secret: string;
}
export interface CredentialBinding { repositoryId: string; repositoryName: string; usageType: string; createdAt: string }
export interface RepositoryCredentialBindingStatus {
  remoteUrl: string;
  credential: RepositoryCredential | null;
}

export const repositoryCredentialsApi = {
  list: () => request<RepositoryCredential[]>('/api/repository-credentials'),
  create: (input: RepositoryCredentialInput) => request<RepositoryCredential>('/api/repository-credentials', {
    method: 'POST', body: JSON.stringify(input),
  }),
  update: (id: string, input: RepositoryCredentialInput) => request<RepositoryCredential>(`/api/repository-credentials/${id}`, {
    method: 'PUT', body: JSON.stringify(input),
  }),
  validate: (id: string, repositoryUrl: string) => request<RepositoryCredential>(`/api/repository-credentials/${id}/validate`, {
    method: 'POST', body: JSON.stringify({ repositoryUrl }),
  }),
  enable: (id: string) => request<RepositoryCredential>(`/api/repository-credentials/${id}/enable`, { method: 'POST' }),
  disable: (id: string) => request<RepositoryCredential>(`/api/repository-credentials/${id}/disable`, { method: 'POST' }),
  remove: (id: string) => request<void>(`/api/repository-credentials/${id}`, { method: 'DELETE' }),
  bindings: (id: string) => request<CredentialBinding[]>(`/api/repository-credentials/${id}/bindings`),
  repositoryBinding: (repositoryId: string) => request<RepositoryCredentialBindingStatus>(`/api/repositories/${repositoryId}/credential`),
  bindRepository: (repositoryId: string, credentialId: string) => request<RepositoryCredentialBindingStatus>(`/api/repositories/${repositoryId}/credential`, {
    method: 'PUT', body: JSON.stringify({ credentialId }),
  }),
  unbindRepository: (repositoryId: string) => request<void>(`/api/repositories/${repositoryId}/credential`, { method: 'DELETE' }),
};
