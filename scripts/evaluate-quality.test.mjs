import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const evaluator = path.join(root, 'scripts/evaluate-quality.mjs');
const jsonl = (name) => fs.readFileSync(path.join(root, `evaluation/${name}.jsonl`), 'utf8')
  .trim().split(/\r?\n/).map(JSON.parse);
const gold = { retrieval: jsonl('retrieval'), qa: jsonl('qa'), change: jsonl('change') };

function result(passing) {
  return {
    datasetVersion: '1.0.0',
    runId: passing ? 'passing-fixture' : 'failing-fixture',
    retrieval: gold.retrieval.map((item) => ({
      id: item.id,
      latencyMs: passing ? 100 : 6000,
      returnedPaths: passing ? item.expectedPaths : [],
    })),
    qa: gold.qa.map((item) => ({
      id: item.id,
      latencyMs: passing ? 100 : 6000,
      claims: [{ text: item.answerKey, cited: passing, supported: passing }],
      assessor: { type: 'HUMAN', name: 'test-reviewer', assessedAt: '2026-08-26T00:00:00Z' },
    })),
    change: gold.change.map((item) => ({
      id: item.id,
      latencyMs: passing ? 100 : 6000,
      predictedFiles: passing ? item.expectedFiles : [],
    })),
  };
}

function evaluate(payload) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'analyzer-eval-'));
  const file = path.join(directory, 'result.json');
  fs.writeFileSync(file, JSON.stringify(payload));
  try {
    return spawnSync(process.execPath, [evaluator, '--results', file], { encoding: 'utf8' });
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
}

test('perfect observed run passes every release threshold', () => {
  const run = evaluate(result(true));
  assert.equal(run.status, 0, run.stderr);
  const report = JSON.parse(run.stdout);
  assert.equal(report.passed, true);
  assert.equal(report.metrics.retrievalRecallAt10, 1);
  assert.equal(report.metrics.p95LatencyMs, 100);
});

test('poor observed run fails the release gate', () => {
  const run = evaluate(result(false));
  assert.equal(run.status, 2, run.stderr);
  assert.equal(JSON.parse(run.stdout).passed, false);
});

test('missing cases cannot be reported as a complete evaluation', () => {
  const payload = result(true);
  payload.retrieval.pop();
  const run = evaluate(payload);
  assert.equal(run.status, 1);
  assert.match(run.stderr, /结果不完整/);
});
