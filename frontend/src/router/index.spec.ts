import { describe, expect, it } from 'vitest';
import { router } from './index';

describe('workspace critical routes', () => {
  it('keeps every shipped workspace capability on one unique route', () => {
    const expected = new Map([
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

    const actual = new Map(
      router.getRoutes()
        .filter(route => typeof route.name === 'string')
        .map(route => [String(route.name), route.path]),
    );

    for (const [name, path] of expected) expect(actual.get(name)).toBe(path);
    expect(new Set(actual.values()).size).toBe(actual.size);
  });

  it('keeps maintenance and system operations behind explicit route metadata', () => {
    const routes = new Map(router.getRoutes().map(route => [String(route.name), route]));

    expect(routes.get('knowledge')?.meta.repositoryMaintain).toBe(true);
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
