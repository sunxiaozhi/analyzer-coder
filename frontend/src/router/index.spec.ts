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
      ['settings', '/settings'],
      ['accounts', '/accounts'],
    ]);

    const actual = new Map(
      router.getRoutes()
        .filter(route => typeof route.name === 'string')
        .map(route => [String(route.name), route.path]),
    );

    for (const [name, path] of expected) expect(actual.get(name)).toBe(path);
    expect(new Set(actual.values()).size).toBe(actual.size);
  });

  it('routes source search and task center to live implementations', () => {
    const search = router.getRoutes().find(route => route.name === 'search');
    const indexing = router.getRoutes().find(route => route.name === 'indexing');

    expect((search?.components?.default as { __name?: string }).__name).toBe('ChunksM0View');
    expect((indexing?.components?.default as { __name?: string }).__name)
      .toBe('UnifiedIndexJobsView');
  });
});
