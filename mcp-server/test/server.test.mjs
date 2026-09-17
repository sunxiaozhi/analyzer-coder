import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createAnalyzerMcpServer } from '../src/server.mjs';

test('builds the MCP server without making eager backend calls', () => {
  let calls = 0;
  const server = createAnalyzerMcpServer({ request: async () => { calls += 1; } });
  assert.ok(server);
  assert.equal(calls, 0);
});

test('exposes project search and branch context resolution', async () => {
  const session = startMcp({ ANALYZER_API_BASE: 'http://127.0.0.1:8080' });
  try {
    await initialize(session);
    session.send({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} });
    const listed = await waitFor(session.messages, message => message.id === 2);
    assert.deepEqual(listed.result.tools.map(tool => tool.name), ['search_project', 'resolve_project_context', 'list_codegraph_scopes', 'codegraph_explore', 'codegraph_node', 'codegraph_search', 'codegraph_callers', 'codegraph_callees', 'codegraph_impact', 'codegraph_files', 'codegraph_status', 'codegraph_affected']);
  } finally {
    session.child.kill();
  }
});

test('searches code and knowledge through the read-only HTTP endpoint', async () => {
  const repositoryId = '11111111-1111-4111-8111-111111111111';
  const received = {};
  const backend = createServer((request, response) => {
    received.method = request.method;
    received.url = request.url;
    received.authorization = request.headers.authorization;
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify({
      evidence: [
        { sourceType: 'CODE', title: 'RefundService', filePath: 'src/RefundService.java' },
        { sourceType: 'KNOWLEDGE', title: '退款说明', filePath: 'knowledge://card' },
      ],
      retrieval: { degraded: false },
    }));
  });
  await new Promise((resolve, reject) => {
    backend.once('error', reject);
    backend.listen(0, '127.0.0.1', resolve);
  });
  const address = backend.address();
  const session = startMcp({
    ANALYZER_API_BASE: `http://127.0.0.1:${address.port}`,
    ANALYZER_ACCESS_TOKEN: 'search-token',
  });
  try {
    await initialize(session);
    session.send({
      jsonrpc: '2.0', id: 2, method: 'tools/call',
      params: { name: 'search_project', arguments: { repositoryId, query: '退款 幂等', limit: 10 } },
    });
    const called = await waitFor(session.messages, message => message.id === 2);
    assert.equal(called.result.isError, undefined);
    assert.equal(called.result.structuredContent.evidence.length, 2);
    assert.equal(received.method, 'GET');
    assert.equal(
      received.url,
      `/api/repositories/${repositoryId}/evidence-search?${new URLSearchParams({ query: '退款 幂等', limit: '10' })}`,
    );
    assert.equal(received.authorization, 'Bearer search-token');
  } finally {
    session.child.kill();
    await new Promise(resolve => backend.close(resolve));
  }
});

test('resolves the branch and forwards its pinned context to search', async () => {
  const repositoryId = '11111111-1111-4111-8111-111111111111';
  const branchId = '22222222-2222-4222-8222-222222222222';
  const contextId = '33333333-3333-4333-8333-333333333333';
  const received = [];
  const backend = createServer(async (request, response) => {
    let body = '';
    for await (const chunk of request) body += chunk;
    received.push({ url: request.url, method: request.method, context: request.headers['x-branch-context'], body });
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify(request.url.endsWith('/contexts')
      ? { repositoryId, branchId, contextId, branchName: 'release', commitSha: 'abc' }
      : { evidence: [], retrieval: { degraded: false } }));
  });
  await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve));
  const session = startMcp({ ANALYZER_API_BASE: `http://127.0.0.1:${backend.address().port}`, ANALYZER_ACCESS_TOKEN: 'test-token' });
  try {
    await initialize(session);
    session.send({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'search_project', arguments: { repositoryId, branchId, query: 'refund' } } });
    const called = await waitFor(session.messages, message => message.id === 2);
    assert.equal(called.result.isError, undefined);
    assert.equal(called.result.structuredContent.context.contextId, contextId);
    assert.equal(received.length, 2);
    assert.equal(received[0].method, 'POST');
    assert.deepEqual(JSON.parse(received[0].body), { branchId });
    assert.equal(received[1].context, contextId);
  } finally {
    session.child.kill();
    await new Promise(resolve => backend.close(resolve));
  }
});

test('proxies graph discovery through the authenticated backend MCP endpoint', async () => {
  const received = [];
  const backend = createServer(async (request, response) => {
    let body = '';
    for await (const chunk of request) body += chunk;
    received.push({ path: request.url, token: request.headers.authorization, body: JSON.parse(body) });
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ jsonrpc: '2.0', id: 1, result: { content: [{ type: 'text', text: '{}' }], structuredContent: { projects: [] }, isError: false } }));
  });
  await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve));
  const session = startMcp({ ANALYZER_API_BASE: `http://127.0.0.1:${backend.address().port}`, ANALYZER_ACCESS_TOKEN: 'graph-token' });
  try {
    await initialize(session);
    session.send({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'list_codegraph_scopes', arguments: { page: 1 } } });
    const called = await waitFor(session.messages, message => message.id === 2);
    assert.equal(called.result.structuredContent.projects.length, 0);
    assert.equal(received[0].path, '/api/mcp');
    assert.equal(received[0].token, 'Bearer graph-token');
    assert.equal(received[0].body.params.name, 'list_codegraph_scopes');
  } finally {
    session.child.kill();
    await new Promise(resolve => backend.close(resolve));
  }
});
function startMcp(extraEnvironment) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const child = spawn(process.execPath, [path.join(root, 'src/server.mjs')], {
    cwd: root,
    env: {
      ...process.env,
      ANALYZER_SESSION_TOKEN: 'test-session',
      ANALYZER_CSRF_TOKEN: 'test-csrf',
      ...extraEnvironment,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const messages = [];
  let buffer = '';
  child.stdout.setEncoding('utf8');
  child.stdout.on('data', chunk => {
    buffer += chunk;
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() ?? '';
    for (const line of lines.filter(Boolean)) messages.push(JSON.parse(line));
  });
  return {
    child,
    messages,
    send: message => child.stdin.write(`${JSON.stringify(message)}\n`),
  };
}

async function initialize(session) {
  session.send({
    jsonrpc: '2.0', id: 1, method: 'initialize',
    params: {
      protocolVersion: '2025-06-18', capabilities: {},
      clientInfo: { name: 'protocol-test', version: '1.0.0' },
    },
  });
  await waitFor(session.messages, message => message.id === 1);
  session.send({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} });
}

async function waitFor(messages, predicate) {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    const found = messages.find(predicate);
    if (found) return found;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw new Error(`Timed out waiting for MCP response; received ${JSON.stringify(messages)}`);
}
