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

test('exposes only the focused project search tool', async () => {
  const session = startMcp({ ANALYZER_API_BASE: 'http://127.0.0.1:8080' });
  try {
    await initialize(session);
    session.send({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} });
    const listed = await waitFor(session.messages, message => message.id === 2);
    assert.deepEqual(listed.result.tools.map(tool => tool.name), ['search_project']);
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
