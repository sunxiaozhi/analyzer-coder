import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { configFromEnv, formatResult, run } from './ci-task-review.mjs';

const repositoryId = '0f0479d0-c0c8-4ce1-853a-2b45790c54d7';
const reviewId = '8d508a37-1cba-42d4-a3d1-7b7796c36638';
const headCommit = 'a'.repeat(40);

function environment(overrides = {}) {
  return {
    ANALYZER_BASE_URL: 'http://127.0.0.1:8080',
    ANALYZER_REPOSITORY_ID: repositoryId,
    ANALYZER_REVIEW_ID: reviewId,
    ANALYZER_HEAD_COMMIT: headCommit,
    ANALYZER_SESSION_COOKIE: 'session-secret',
    ANALYZER_CSRF_TOKEN: 'csrf-secret',
    ...overrides,
  };
}

test('loads explicit test and approval reports without accepting insecure remote HTTP', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'analyzer-ci-'));
  const tests = path.join(directory, 'tests.json');
  const approvals = path.join(directory, 'approvals.json');
  fs.writeFileSync(tests, JSON.stringify([{ key: 'backend-tests', status: 'PASSED' }]));
  fs.writeFileSync(approvals, JSON.stringify([{ accountId: repositoryId, status: 'APPROVED' }]));

  const config = configFromEnv(environment({
    ANALYZER_TEST_REPORTS_FILE: tests,
    ANALYZER_APPROVAL_REPORTS_FILE: approvals,
  }));

  assert.equal(config.tests[0].key, 'backend-tests');
  assert.equal(config.approvals[0].status, 'APPROVED');
  assert.throws(
    () => configFromEnv(environment({ ANALYZER_BASE_URL: 'http://analyzer.example.com' })),
    /必须使用 HTTPS/,
  );
});

test('returns the deterministic exit code and never prints credentials', async () => {
  let request;
  const messages = [];
  const result = {
    decision: 'FAIL', exitCode: 1, policyVersion: 'deterministic-ci-v1',
    reviewId, headCommit,
    blockingFindings: [{ code: 'REQUIRED_TEST_NOT_REPORTED', title: '缺少测试', detail: 'backend-tests' }],
    advisories: [{ code: 'MODEL_SUGGESTIONS_IGNORED', title: '模型不参与', detail: '已排除' }],
  };
  const exitCode = await run(
    environment(),
    async (url, init) => {
      request = { url, init };
      return { ok: true, json: async () => result };
    },
    { log: message => messages.push(message), error: message => messages.push(message) },
  );

  assert.equal(exitCode, 1);
  assert.equal(request.url, `http://127.0.0.1:8080/api/repositories/${repositoryId}/task-reviews/${reviewId}/ci-check`);
  assert.equal(request.init.headers.Cookie, 'AC_SESSION=session-secret');
  assert.deepEqual(JSON.parse(request.init.body), { headCommit, tests: [], approvals: [] });
  assert.match(messages.join('\n'), /REQUIRED_TEST_NOT_REPORTED/);
  assert.doesNotMatch(messages.join('\n'), /session-secret|csrf-secret/);
});

test('uses exit code two for transport or API contract failures', async () => {
  const errors = [];
  const exitCode = await run(
    environment(),
    async () => ({ ok: false, status: 403, statusText: 'Forbidden', json: async () => ({ code: 'CSRF_INVALID', message: '拒绝' }) }),
    { log() {}, error: message => errors.push(message) },
  );
  assert.equal(exitCode, 2);
  assert.match(errors[0], /CSRF_INVALID/);
});

test('formats blocking and advisory findings as distinct output', () => {
  const text = formatResult({
    decision: 'PASS', policyVersion: 'deterministic-ci-v1', reviewId, headCommit,
    blockingFindings: [],
    advisories: [{ code: 'PARTIAL_CHANGE_DOES_NOT_FAIL_CI', title: '不完整', detail: '仅提示' }],
  });
  assert.match(text, /^Analyzer CI: PASS/);
  assert.match(text, /\[INFO\] PARTIAL_CHANGE_DOES_NOT_FAIL_CI/);
});
