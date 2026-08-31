import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createAnalyzerMcpServer } from '../src/server.mjs';

test('builds the official MCP server without making eager backend calls', () => {
  let calls = 0;
  const server = createAnalyzerMcpServer({
    request: async () => {
      calls += 1;
      throw new Error('not expected during registration');
    },
  });

  assert.ok(server);
  assert.equal(calls, 0);
});

test('negotiates MCP and lists the expected thin-adapter tools over stdio', async () => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const child = spawn(process.execPath, [path.join(root, 'src/server.mjs')], {
    cwd: root,
    env: {
      ...process.env,
      ANALYZER_API_BASE: 'http://127.0.0.1:8080',
      ANALYZER_SESSION_TOKEN: 'protocol-test-session',
      ANALYZER_CSRF_TOKEN: 'protocol-test-csrf',
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
  const send = message => child.stdin.write(`${JSON.stringify(message)}\n`);
  try {
    send({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: {
        protocolVersion: '2025-06-18', capabilities: {},
        clientInfo: { name: 'protocol-test', version: '1.0.0' },
      },
    });
    const initialized = await waitFor(messages, message => message.id === 1);
    assert.equal(initialized.result.serverInfo.name, 'analyzer-coder');
    send({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} });
    send({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} });
    const listed = await waitFor(messages, message => message.id === 2);
    assert.deepEqual(
      listed.result.tools.map(tool => tool.name).sort(),
      [
        'get_evidence',
        'get_required_tests',
        'get_rules_for_symbol',
        'get_stale_knowledge',
        'get_task_context',
        'report_task_outcome',
        'review_change',
      ],
    );
  } finally {
    child.kill();
  }
});

test('calls the task-context HTTP API with the same session and CSRF boundary', async () => {
  const repositoryId = '11111111-1111-4111-8111-111111111111';
  const received = {};
  const backend = createServer((request, response) => {
    const chunks = [];
    request.on('data', chunk => chunks.push(chunk));
    request.on('end', () => {
      received.method = request.method;
      received.url = request.url;
      received.cookie = request.headers.cookie;
      received.csrf = request.headers['x-csrf-token'];
      received.body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({
        repositoryId,
        repositoryName: 'protocol-fixture',
        snapshotId: '22222222-2222-4222-8222-222222222222',
        commitSha: 'abc123',
        task: received.body.task,
        taskReviewId: null,
        entries: [],
        requiredTests: [],
        requiredApprovals: [],
        unknowns: [],
        budget: { maxItems: 9, maxChars: 8000, maxTokens: 2000, usedItems: 0, usedChars: 0 },
      }));
    });
  });
  await new Promise((resolve, reject) => {
    backend.once('error', reject);
    backend.listen(0, '127.0.0.1', resolve);
  });
  const address = backend.address();
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const child = spawn(process.execPath, [path.join(root, 'src/server.mjs')], {
    cwd: root,
    env: {
      ...process.env,
      ANALYZER_API_BASE: `http://127.0.0.1:${address.port}`,
      ANALYZER_SESSION_TOKEN: 'same-http-session',
      ANALYZER_CSRF_TOKEN: 'same-http-csrf',
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
  const send = message => child.stdin.write(`${JSON.stringify(message)}\n`);
  try {
    send({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: {
        protocolVersion: '2025-06-18', capabilities: {},
        clientInfo: { name: 'tool-call-test', version: '1.0.0' },
      },
    });
    await waitFor(messages, message => message.id === 1);
    send({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} });
    send({
      jsonrpc: '2.0', id: 2, method: 'tools/call',
      params: {
        name: 'get_task_context',
        arguments: {
          repositoryId,
          task: '验证支付入口',
          maxItems: 9,
          maxChars: 8000,
          maxTokens: 2000,
        },
      },
    });
    const called = await waitFor(messages, message => message.id === 2);
    assert.equal(called.result.isError, undefined);
    assert.equal(called.result.structuredContent.commitSha, 'abc123');
    assert.equal(received.method, 'POST');
    assert.equal(received.url, `/api/repositories/${repositoryId}/task-context`);
    assert.equal(received.cookie, 'AC_SESSION=same-http-session');
    assert.equal(received.csrf, 'same-http-csrf');
    assert.deepEqual(received.body, {
      task: '验证支付入口',
      taskReviewId: null,
      maxItems: 9,
      maxChars: 8000,
      maxTokens: 2000,
    });
  } finally {
    child.kill();
    await new Promise(resolve => backend.close(resolve));
  }
});

test('reports a structured immutable task outcome through the HTTP API', async () => {
  const repositoryId = '11111111-1111-4111-8111-111111111111';
  const reviewId = '22222222-2222-4222-8222-222222222222';
  const requestId = '33333333-3333-4333-8333-333333333333';
  const received = {};
  const backend = createServer((request, response) => {
    const chunks = [];
    request.on('data', chunk => chunks.push(chunk));
    request.on('end', () => {
      received.method = request.method;
      received.url = request.url;
      received.csrf = request.headers['x-csrf-token'];
      received.body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({
        id: '44444444-4444-4444-8444-444444444444',
        repositoryId,
        reviewId,
        finalCommit: received.body.finalCommit,
        tests: received.body.tests,
        approvals: received.body.approvals,
        feedback: received.body.feedback,
      }));
    });
  });
  await new Promise((resolve, reject) => {
    backend.once('error', reject);
    backend.listen(0, '127.0.0.1', resolve);
  });
  const address = backend.address();
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const child = spawn(process.execPath, [path.join(root, 'src/server.mjs')], {
    cwd: root,
    env: {
      ...process.env,
      ANALYZER_API_BASE: `http://127.0.0.1:${address.port}`,
      ANALYZER_SESSION_TOKEN: 'outcome-session',
      ANALYZER_CSRF_TOKEN: 'outcome-csrf',
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
  const send = message => child.stdin.write(`${JSON.stringify(message)}\n`);
  try {
    send({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: {
        protocolVersion: '2025-06-18', capabilities: {},
        clientInfo: { name: 'outcome-test', version: '1.0.0' },
      },
    });
    await waitFor(messages, message => message.id === 1);
    send({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} });
    send({
      jsonrpc: '2.0', id: 2, method: 'tools/call',
      params: {
        name: 'report_task_outcome',
        arguments: {
          repositoryId,
          reviewId,
          clientRequestId: requestId,
          finalCommit: 'A'.repeat(40),
          summary: '实现与验证已完成',
          tests: [{ key: 'npm test', status: 'PASSED' }],
          approvals: [],
          feedback: [{
            kind: 'FALSE_NEGATIVE', targetType: 'FILE', targetKey: 'src/missed.ts',
            comment: '人工复核确认漏掉该文件',
          }],
        },
      },
    });
    const called = await waitFor(messages, message => message.id === 2);
    assert.equal(called.result.isError, undefined);
    assert.equal(called.result.structuredContent.id, '44444444-4444-4444-8444-444444444444');
    assert.equal(received.method, 'POST');
    assert.equal(received.url, `/api/repositories/${repositoryId}/task-reviews/${reviewId}/outcomes`);
    assert.equal(received.csrf, 'outcome-csrf');
    assert.deepEqual(received.body, {
      clientRequestId: requestId,
      finalCommit: 'a'.repeat(40),
      summary: '实现与验证已完成',
      tests: [{ key: 'npm test', status: 'PASSED', evidenceUrl: null }],
      approvals: [],
      feedback: [{
        kind: 'FALSE_NEGATIVE', targetType: 'FILE', targetKey: 'src/missed.ts',
        comment: '人工复核确认漏掉该文件', evidenceUrls: [],
        knowledgeId: null, knowledgeUpdateAssessment: null,
      }],
    });
  } finally {
    child.kill();
    await new Promise(resolve => backend.close(resolve));
  }
});

async function waitFor(messages, predicate) {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    const found = messages.find(predicate);
    if (found) return found;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw new Error(`Timed out waiting for MCP response; received ${JSON.stringify(messages)}`);
}
