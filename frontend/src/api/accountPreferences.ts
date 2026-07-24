import { request } from './http';

export interface CurrentRepositoryPreference {
  repositoryId: string | null;
}

export function getCurrentRepositoryPreference() {
  return request<CurrentRepositoryPreference>('/api/auth/preferences/current-repository');
}

export function updateCurrentRepositoryPreference(repositoryId: string | null) {
  return request<CurrentRepositoryPreference>('/api/auth/preferences/current-repository', {
    method: 'PUT',
    body: JSON.stringify({ repositoryId }),
  });
}
