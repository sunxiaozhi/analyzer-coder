import { request } from './http';
import type { Repository } from '@/types/api';

export type ImportSourceType = 'REMOTE_GIT' | 'GITLAB';
export interface RepositoryImportJob {
  id: string;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELED';
  currentStep: string;
  errorMessage: string | null;
  resultRepositoryId: string | null;
  projectDraftId: string | null;
}
type RemoteInput = {
  name: string;
  url: string;
  branch?: string;
  sourceType: ImportSourceType;
  credentialId?: string;
  projectDraftId?: string;
};
export const sourceImportsApi = {
  remoteJob: (input: RemoteInput) => request<RepositoryImportJob>('/api/repository-imports/remote-jobs', { method: 'POST', body: JSON.stringify(input) }),
  job: (id: string) => request<RepositoryImportJob>(`/api/repository-imports/jobs/${id}`),
  zip: (name: string, file: File) => {
    const body = new FormData();
    body.set('name', name);
    body.set('file', file);
    return request<Repository>('/api/repository-imports/zip', { method: 'POST', body });
  },
};
