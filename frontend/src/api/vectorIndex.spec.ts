import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './http';
import { vectorIndexApi } from './vectorIndex';

vi.mock('./http', () => ({ request: vi.fn() }));

describe('vector index API branch boundaries', () => {
  beforeEach(() => vi.mocked(request).mockReset());

  it('pins every vector-index read to the selected branch context', async () => {
    await vectorIndexApi.summary('repo-a', 'context-a');
    expect(request).toHaveBeenLastCalledWith(
      '/api/repositories/repo-a/vector-index/summary',
      { headers: { 'X-Branch-Context': 'context-a' } },
    );

    await vectorIndexApi.chunks('repo-a', 'context-a', {
      pageNum: 1,
      pageSize: 15,
    });
    expect(request).toHaveBeenLastCalledWith(
      '/api/repositories/repo-a/vector-index/chunks?pageNum=1&pageSize=15',
      { headers: { 'X-Branch-Context': 'context-a' } },
    );

    await vectorIndexApi.knowledge('repo-a', 'context-a', {
      pageNum: 2,
      pageSize: 30,
      status: 'MISSING',
    });
    expect(request).toHaveBeenLastCalledWith(
      '/api/repositories/repo-a/vector-index/knowledge?pageNum=2&pageSize=30&status=MISSING',
      { headers: { 'X-Branch-Context': 'context-a' } },
    );
  });
});
