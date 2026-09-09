import { describe, expect, it, vi } from 'vitest';
import { router } from './index';

vi.mock('@/stores/authStore', () => ({ useAuthStore: () => ({ restore: async () => {}, authenticated: true, isAdmin: false, account: { mustChangePassword: false } }) }));
vi.mock('@/stores/repositoryStore', () => ({ useRepositoryStore: () => ({ initialized: true, repositories: [{ capabilities: { canUpdate: false } }], selectedRepository: { capabilities: { canUpdate: false } } }) }));

describe('workspace critical routes', () => {
  it('keeps every shipped workspace capability on one unique route', () => {
    const expected = new Map([
      ['help', '/help'],
      ['mcp', '/mcp'],
      ['ask', '/ask'],
      ['knowledge', '/knowledge'],
      ['graph', '/graph'],
      ['search', '/search'],
      ['repositories', '/repositories'],
      ['indexing', '/indexing'],
      ['overview', '/overview'],
      ['change-impact', '/change-impact'],
      ['settings', '/settings'],
      ['accounts', '/accounts'],
      ['audit', '/audit'],
    ]);

    const namedRoutes = router.getRoutes()
      .filter(route => typeof route.name === 'string');
    const actual = new Map(
      namedRoutes.map(route => [String(route.name), route.path]),
    );

    for (const [name, path] of expected) expect(actual.get(name)).toBe(path);
    expect(new Set(namedRoutes.map(route => String(route.name))).size).toBe(namedRoutes.length);
    expect(new Set(namedRoutes.map(route => route.path)).size).toBe(namedRoutes.length);
  });

  it('keeps maintenance and system operations behind explicit route metadata', () => {
    const routes = new Map(router.getRoutes().map(route => [String(route.name), route]));

    expect(routes.get('knowledge')?.meta.repositoryRead).toBe(true);
    expect(routes.get('repositories')?.meta.projectManage).toBe(true);
    for (const name of ['indexing', 'settings', 'accounts', 'audit']) {
      expect(routes.get(name)?.meta.admin).toBe(true);
    }
  });

  it('routes the code evidence workbench and task center to live implementations', () => {
    const search = router.getRoutes().find(route => route.name === 'search');
    const graph = router.getRoutes().find(route => route.name === 'graph');
    const indexing = router.getRoutes().find(route => route.name === 'indexing');
    const audit = router.getRoutes().find(route => route.name === 'audit');

    expect((search?.components?.default as { __name?: string }).__name).toBe('ChunksM0View');
    expect(graph?.redirect).toBeTypeOf('function');
    expect(graph?.components).toBeUndefined();
    expect((indexing?.components?.default as { __name?: string }).__name)
      .toBe('UnifiedIndexJobsView');
    expect((audit?.components?.default as { __name?: string }).__name).toBe('AuditLogsView');
  });
});

it('lets a READ-only account import its own repository and open knowledge evidence', async () => {
  await router.push('/mcp');
  expect(router.currentRoute.value.name).toBe('mcp');
  await router.push('/repositories');
  expect(router.currentRoute.value.name).toBe('repositories');
  await router.push('/knowledge?cardId=published-card');
  expect(router.currentRoute.value.name).toBe('knowledge');
  await router.push('/accounts');
  expect(router.currentRoute.value.name).toBe('overview');
});
