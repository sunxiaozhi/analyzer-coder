import { request } from './http';
import type { PageResult } from '@/types/pagination';
import type { AccountRole, AccountSummary, AuditEvent, CreatedAccount } from '@/types/security';

export interface AccountInput { username: string; displayName: string; role: AccountRole; temporaryPassword?: string }

export const accountsApi = {
  page: (params: { query?: string; pageNum: number; pageSize: number }) => {
    const search = new URLSearchParams({ pageNum: String(params.pageNum), pageSize: String(params.pageSize) });
    if (params.query?.trim()) search.set('query', params.query.trim());
    return request<PageResult<AccountSummary>>(`/api/accounts/page?${search}`);
  },
  create: (input: AccountInput) => request<CreatedAccount>('/api/accounts', { method: 'POST', body: JSON.stringify(input) }),
  update: (id: string, input: Partial<Pick<AccountSummary, 'displayName' | 'role' | 'version'> & { enabled: boolean }>) =>
    request<AccountSummary>(`/api/accounts/${id}`, { method: 'PATCH', body: JSON.stringify(input) }),
  resetPassword: (id: string) => request<{ temporaryPassword: string }>(`/api/accounts/${id}/reset-password`, { method: 'POST' }),
  unlock: (id: string) => request<void>(`/api/accounts/${id}/unlock`, { method: 'POST' }),
  audit: (limit = 200, offset = 0) => request<AuditEvent[]>(`/api/accounts/audit?limit=${limit}&offset=${offset}`),
};