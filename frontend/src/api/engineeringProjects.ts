import { request } from './http';

export interface EngineeringProjectRepository {
  repositoryId: string;
  repositoryName: string;
  serviceName: string;
}

export interface EngineeringContract {
  id: string;
  contractKey: string;
  name: string;
  providerRepositoryId: string;
  consumerRepositoryId: string;
  providerEvidencePath: string;
  consumerEvidencePath: string;
  providerEvidenceCurrent: boolean;
  consumerEvidenceCurrent: boolean;
  current: boolean;
}

export interface EngineeringProject {
  id: string;
  name: string;
  description: string;
  version: number;
  repositories: EngineeringProjectRepository[];
  contracts: EngineeringContract[];
  createdAt: string;
  updatedAt: string;
}

export interface EngineeringProjectInput {
  name: string;
  description: string;
  expectedVersion?: number | null;
  repositories: { repositoryId: string; serviceName: string }[];
  contracts: {
    id?: string | null;
    contractKey: string;
    name: string;
    providerRepositoryId: string;
    consumerRepositoryId: string;
    providerEvidencePath: string;
    consumerEvidencePath: string;
  }[];
}

export const engineeringProjectsApi = {
  list: () => request<EngineeringProject[]>('/api/engineering-projects'),
  create: (body: EngineeringProjectInput) => request<EngineeringProject>('/api/engineering-projects', {
    method: 'POST', body: JSON.stringify(body),
  }),
  update: (id: string, body: EngineeringProjectInput) => request<EngineeringProject>(`/api/engineering-projects/${id}`, {
    method: 'PUT', body: JSON.stringify(body),
  }),
  remove: (id: string, expectedVersion: number) => request<void>(`/api/engineering-projects/${id}?expectedVersion=${expectedVersion}`, {
    method: 'DELETE',
  }),
};
