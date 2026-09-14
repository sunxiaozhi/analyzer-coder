import { request } from './http';
import type { Repository } from '@/types/api';

export type ProjectDraftStatus = 'DRAFT' | 'SOURCE_CONFIGURED' | 'IMPORTING' | 'READY' | 'FAILED';
export interface ProjectDraft {
  id: string;
  name: string;
  description: string;
  sourceType: string | null;
  sourceLocation: string | null;
  credentialId: string | null;
  lifecycleStatus: ProjectDraftStatus;
  resultRepositoryId: string | null;
  error: string | null;
  version: number;
  updatedAt: string;
}

const base = '/api/repository-project-drafts';
export const projectDraftsApi = {
  list: () => request<ProjectDraft[]>(base),
  create: (name: string, description: string) => request<ProjectDraft>(base, {
    method: 'POST', body: JSON.stringify({ name, description }),
  }),
  configure: (
    draft: ProjectDraft,
    sourceType: string,
    sourceLocation: string,
    credentialId?: string,
  ) => request<ProjectDraft>(`${base}/${draft.id}/source`, {
    method: 'PATCH',
    body: JSON.stringify({ version: draft.version, sourceType, sourceLocation, credentialId }),
  }),
  complete: (draftId: string, repository: Repository) => request<ProjectDraft>(`${base}/${draftId}/complete`, {
    method: 'POST', body: JSON.stringify({ repositoryId: repository.id }),
  }),
};
