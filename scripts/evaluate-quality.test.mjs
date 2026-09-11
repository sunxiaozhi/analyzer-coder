import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const evaluator = path.join(repositoryRoot, 'scripts/evaluate-quality.mjs');

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'analyzer-eval-'));
  const evaluation = path.join(root, 'evaluation');
  fs.mkdirSync(evaluation);
  fs.writeFileSync(path.join(root, 'fixture.txt'), 'fixture');
  fs.writeFileSync(path.join(evaluation, 'retrieval.jsonl'), `${JSON.stringify({ id: 'RET-1', repositoryType: 'JAVA_SPRING', expectedPaths: ['fixture.txt'] })}\n`);
  fs.writeFileSync(path.join(evaluation, 'qa.jsonl'), `${JSON.stringify({ id: 'QA-1', repositoryType: 'JAVA_SPRING', evidence: [{ path: 'fixture.txt' }], curation: { method: 'HUMAN', reviewedAt: '2026-09-11' } })}\n`);
  fs.writeFileSync(path.join(evaluation, 'manifest.json'), JSON.stringify({ datasetVersion: '3-test', repositoryTypes: ['JAVA_SPRING'], datasets: { retrieval: { path: 'evaluation/retrieval.jsonl', expectedCount: 1 }, qa: { path: 'evaluation/qa.jsonl', expectedCount: 1 } } }));
  fs.writeFileSync(path.join(evaluation, 'thresholds.json'), JSON.stringify({ retrievalRecallAt10: 0.85, citationCoverageRate: 0.95, statementSupportRate: 0.9, p95LatencyMs: 5000 }));
  return root;
}

function run(root, payload) {
  const file = path.join(root, 'result.json');
  fs.writeFileSync(file, JSON.stringify(payload));
  return spawnSync(process.execPath, [evaluator, '--results', file, '--root', root], { encoding: 'utf8' });
}

function perfect() {
  return { datasetVersion: '3-test', runId: 'run', retrieval: [{ id: 'RET-1', latencyMs: 100, returnedPaths: ['fixture.txt'] }], qa: [{ id: 'QA-1', latencyMs: 100, claims: [{ cited: true, supported: true }], assessor: { type: 'HUMAN', name: 'reviewer', assessedAt: '2026-09-11T00:00:00Z' } }] };
}

test('repository gold matches the minimal retrieval scope', () => {
  const result = spawnSync(process.execPath, [evaluator, '--validate'], { cwd: repositoryRoot, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  const report = JSON.parse(result.stdout);
  assert.deepEqual(Object.keys(report.counts), ['retrieval', 'qa']);
  assert.equal(report.scoreable, true);
});

test('perfect retrieval and cited answers pass', () => {
  const root = fixture();
  try {
    const result = run(root, perfect());
    assert.equal(result.status, 0, result.stderr);
    assert.equal(JSON.parse(result.stdout).passed, true);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('missing retrieval evidence fails the gate', () => {
  const root = fixture();
  try {
    const payload = perfect();
    payload.retrieval[0].returnedPaths = [];
    const result = run(root, payload);
    assert.equal(result.status, 2, result.stderr);
    assert.equal(JSON.parse(result.stdout).metrics.retrievalRecallAt10, 0);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('missing cases cannot be reported as complete', () => {
  const root = fixture();
  try {
    const payload = perfect();
    payload.qa = [];
    const result = run(root, payload);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /结果不完整/);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
