import { request } from './http';

export type LlmAvailability =
  | 'UNCONFIGURED'
  | 'UNTESTED'
  | 'AVAILABLE'
  | 'DEGRADED'
  | 'UNAVAILABLE';

export interface LlmProvider {
  id: string | null;
  version: number;
  name: string;
  providerType: 'OPENAI_COMPATIBLE';
  baseUrl: string;
  model: string;
  connectTimeoutMs: number;
  requestTimeoutMs: number;
  maxOutputTokens: number;
  temperature: number;
  streamingEnabled: boolean;
  secretConfigured: boolean;
  fingerprint: string | null;
  availability: LlmAvailability;
  latestCheckId: string | null;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  lastErrorCode: string | null;
  breakerState: 'CLOSED' | 'OPEN';
  createdAt: string | null;
}

export interface LlmProviderInput {
  name: string;
  providerType: 'OPENAI_COMPATIBLE';
  baseUrl: string;
  model: string;
  connectTimeoutMs: number;
  requestTimeoutMs: number;
  maxOutputTokens: number;
  temperature: number;
  streamingEnabled: boolean;
  secretAction: 'KEEP' | 'REPLACE' | 'CLEAR';
  apiKey?: string;
}

export interface VectorModel {
  id: string;
  name: string;
  providerType: 'LOCAL_HASH' | 'OPENAI_COMPATIBLE';
  baseUrl: string | null;
  model: string;
  dimension: number;
  requestTimeoutMs: number;
  retrievalCapability: 'CHARACTER_HASH' | 'SEMANTIC_EMBEDDING';
  capabilityLabel: '字符相似度' | '语义检索';
  limitations: string[];
  secretConfigured: boolean;
  active: boolean;
  activationVersion: number;
  createdAt: string;
  activatedAt: string | null;
}

export interface VectorModelInput {
  name: string;
  providerType: 'LOCAL_HASH' | 'OPENAI_COMPATIBLE';
  baseUrl: string;
  model: string;
  dimension: number;
  requestTimeoutMs: number;
  secretAction: 'KEEP' | 'REPLACE' | 'CLEAR';
  apiKey?: string;
}

export interface VectorModelCheck {
  configId: string;
  available: boolean;
  dimension: number;
  retrievalCapability: 'CHARACTER_HASH' | 'SEMANTIC_EMBEDDING';
  capabilityLabel: '字符相似度' | '语义检索';
  durationMs: number;
  errorCode: string | null;
  errorSummary: string | null;
}

export interface LlmCheckStage {
  stage: string;
  status: 'SUCCEEDED' | 'FAILED' | 'CANCELED';
  durationMs: number;
  errorCode: string | null;
}

export interface LlmConnectivityCheck {
  id: string;
  configId: string | null;
  fingerprint: string;
  endpointHost: string;
  model: string;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELED';
  availability: LlmAvailability;
  currentStage: string;
  stages: LlmCheckStage[];
  errorCode: string | null;
  errorSummary: string | null;
  totalDurationMs: number | null;
  connectDurationMs: number | null;
  firstTokenDurationMs: number | null;
  requestId: string;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
}

export const llmSettingsApi = {
  providers: () => request<LlmProvider[]>('/api/settings/llm/providers'),
  createProvider: (input: LlmProviderInput) =>
    request<LlmProvider>('/api/settings/llm/providers', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  updateProvider: (id: string, input: LlmProviderInput) =>
    request<LlmProvider>(`/api/settings/llm/providers/${id}`, {
      method: 'PUT',
      body: JSON.stringify(input),
    }),
  provider: () => request<LlmProvider>('/api/settings/llm/provider'),
  versions: () => request<LlmProvider[]>('/api/settings/llm/provider/versions'),
  save: (input: LlmProviderInput) =>
    request<LlmProvider>('/api/settings/llm/provider', {
      method: 'PUT',
      body: JSON.stringify(input),
    }),
  startCheck: (configId: string) =>
    request<LlmConnectivityCheck>('/api/settings/llm/connectivity-checks', {
      method: 'POST',
      body: JSON.stringify({ configId }),
    }),
  check: (checkId: string) =>
    request<LlmConnectivityCheck>(`/api/settings/llm/connectivity-checks/${checkId}`),
  cancelCheck: (checkId: string) =>
    request<LlmConnectivityCheck>(`/api/settings/llm/connectivity-checks/${checkId}/cancel`, {
      method: 'POST',
    }),
  vectorModels: () => request<VectorModel[]>('/api/settings/llm/vector-models'),
  createVectorModel: (input: VectorModelInput) =>
    request<VectorModel>('/api/settings/llm/vector-models', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  updateVectorModel: (id: string, input: VectorModelInput) =>
    request<VectorModel>(`/api/settings/llm/vector-models/${id}`, {
      method: 'PUT',
      body: JSON.stringify(input),
    }),
  checkVectorModel: (id: string) =>
    request<VectorModelCheck>(`/api/settings/llm/vector-models/${id}/check`, {
      method: 'POST',
    }),
  activateVectorModel: (id: string, expectedActivationVersion: number) =>
    request<VectorModel>(
      `/api/settings/llm/vector-models/${id}/activate?expectedActivationVersion=${expectedActivationVersion}`,
      { method: 'POST' },
    ),
};
