import { request } from './http';
export interface AccessToken { id: string; name: string; prefix: string; createdAt: string; expiresAt: string; lastUsedAt: string | null; revokedAt: string | null }
export const accessTokensApi = {
  list: (accountId: string) => request<AccessToken[]>(`/api/accounts/${accountId}/access-tokens`),
  create: (accountId: string, name: string, expiresInDays: number) => request<{ token: AccessToken; rawToken: string }>(`/api/accounts/${accountId}/access-tokens`, { method: 'POST', body: JSON.stringify({ name, expiresInDays }) }),
  revoke: (accountId: string, tokenId: string) => request<{ revoked: boolean }>(`/api/accounts/${accountId}/access-tokens/${tokenId}`, { method: 'DELETE' }),
};
