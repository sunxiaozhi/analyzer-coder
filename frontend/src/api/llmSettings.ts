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
  active: boolean;
  activeConfigId: string | null;
  activationVersion: number;
  latestCheckId: string | null;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  lastErrorCode: string | null;
  breakerState: 'CLOSED' | 'OPEN';
  createdAt: string | null;
  activatedAt: string | null;
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
  activate: (
    configId: string,
    input: { latestCheckId: string; fingerprint: string; expectedActivationVersion: number },
  ) =>
    request<LlmProvider>(`/api/settings/llm/provider/${configId}/activate`, {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  deactivate: (expectedActivationVersion: number) =>
    request<LlmProvider>(
      `/api/settings/llm/provider/deactivate?expectedActivationVersion=${expectedActivationVersion}`,
      { method: 'POST' },
    ),
};
