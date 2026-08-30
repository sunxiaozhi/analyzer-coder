import assert from 'node:assert/strict';
import test from 'node:test';
import { AnalyzerApiClient, AnalyzerApiError } from '../src/api-client.mjs';

test('forwards the Analyzer session and CSRF token without exposing them in the body', async () => {
  let observed;
  const client = new AnalyzerApiClient({
    baseUrl: 'http://127.0.0.1:8080/',
    sessionToken: 'session-secret',
    csrfToken: 'csrf-secret',
    fetchImpl: async (url, init) => {
      observed = { url, init };
      return new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    },
  });

  const result = await client.request('/api/repositories/id/task-context', {
    method: 'POST',
    body: JSON.stringify({ task: 'review' }),
  });

  assert.deepEqual(result, { ok: true });
  assert.equal(observed.url, 'http://127.0.0.1:8080/api/repositories/id/task-context');
  assert.equal(observed.init.headers.get('Cookie'), 'AC_SESSION=session-secret');
  assert.equal(observed.init.headers.get('X-CSRF-Token'), 'csrf-secret');
  assert.doesNotMatch(observed.init.body, /session-secret|csrf-secret/);
});

test('does not send CSRF headers for safe HTTP methods', async () => {
  let headers;
  const client = new AnalyzerApiClient({
    baseUrl: 'http://localhost:8080',
    sessionToken: 'session',
    csrfToken: 'csrf',
    fetchImpl: async (_url, init) => {
      headers = init.headers;
      return new Response(JSON.stringify({ id: 'review' }), { status: 200 });
    },
  });

  await client.request('/api/review');

  assert.equal(headers.get('Cookie'), 'AC_SESSION=session');
  assert.equal(headers.has('X-CSRF-Token'), false);
});

test('preserves stable backend error codes for MCP tool errors', async () => {
  const client = new AnalyzerApiClient({
    baseUrl: 'http://localhost:8080',
    sessionToken: 'session',
    csrfToken: 'csrf',
    fetchImpl: async () => new Response(
      JSON.stringify({ code: 'REPOSITORY_READ_FORBIDDEN', message: '无权读取仓库' }),
      { status: 403, headers: { 'Content-Type': 'application/json' } },
    ),
  });

  await assert.rejects(
    () => client.request('/api/repositories/forbidden/task-context', { method: 'POST', body: '{}' }),
    error => error instanceof AnalyzerApiError
      && error.status === 403
      && error.code === 'REPOSITORY_READ_FORBIDDEN',
  );
});

test('requires environment-provided credentials for stdio authentication', () => {
  assert.throws(
    () => new AnalyzerApiClient({ baseUrl: 'http://localhost:8080', sessionToken: '', csrfToken: '' }),
    /ANALYZER_SESSION_TOKEN/,
  );
});
