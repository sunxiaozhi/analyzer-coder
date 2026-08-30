import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const evaluator = path.join(repositoryRoot, 'scripts/evaluate-quality.mjs');

function writeJsonl(file, items) {
  fs.writeFileSync(file, `${items.map(item => JSON.stringify(item)).join('\n')}\n`);
}

function reviewed() {
  return { status: 'REVIEWED', reviewer: 'test-human', reviewedAt: '2026-08-30T00:00:00Z' };
}

function createFixtureRoot() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'analyzer-eval-gold-'));
  const evaluation = path.join(root, 'evaluation');
  fs.mkdirSync(evaluation);
  fs.writeFileSync(path.join(root, 'fixture.txt'), 'fixture');
  const retrieval = [{ id: 'RET-T', repositoryType: 'JAVA_SPRING', expectedPaths: ['fixture.txt'] }];
  const qa = [{
    id: 'QA-T', repositoryType: 'JAVA_SPRING', answerKey: 'answer',
    evidence: [{ path: 'fixture.txt' }], curation: { method: 'HUMAN', reviewedAt: '2026-08-30' },
  }];
  const change = [{ id: 'CHG-T', repositoryType: 'JAVA_SPRING', expectedFiles: ['fixture.txt'] }];
  const taskReview = Array.from({ length: 10 }, (_, index) => ({
    id: `TR-T${index + 1}`, repositoryType: 'JAVA_SPRING', repositoryKey: 'FIXTURE',
    expectedFiles: ['fixture.txt'], expectedSymbols: ['Fixture'], expectedKnowledgeIds: ['KN-FIXTURE'],
    expectedRequiredTests: ['test'], expectedStaleKnowledgeIds: [], adjudication: reviewed(),
  }));
  const knowledgeDrift = [
    ['KD-T1', 'EXACT_REFERENCE', true],
    ['KD-T2', 'EXACT_REFERENCE', false],
    ['KD-T3', 'PATH_SCOPE', true],
    ['KD-T4', 'SYMBOL_SCOPE', false],
  ].map(([id, ruleType, expectedDrift]) => ({
    id, repositoryType: 'JAVA_SPRING', repositoryKey: 'FIXTURE', ruleType,
    changedFiles: ['fixture.txt'], knowledgeId: 'KN-FIXTURE', expectedDrift,
    adjudication: reviewed(),
  }));
  writeJsonl(path.join(evaluation, 'retrieval.jsonl'), retrieval);
  writeJsonl(path.join(evaluation, 'qa.jsonl'), qa);
  writeJsonl(path.join(evaluation, 'change.jsonl'), change);
  writeJsonl(path.join(evaluation, 'task-review.jsonl'), taskReview);
  writeJsonl(path.join(evaluation, 'knowledge-drift.jsonl'), knowledgeDrift);
  fs.writeFileSync(path.join(evaluation, 'manifest.json'), JSON.stringify({
    datasetVersion: '2.0-test', repositoryTypes: ['JAVA_SPRING'],
    datasets: {
      retrieval: { path: 'evaluation/retrieval.jsonl', expectedCount: 1 },
      qa: { path: 'evaluation/qa.jsonl', expectedCount: 1 },
      change: { path: 'evaluation/change.jsonl', expectedCount: 1 },
      taskReview: { path: 'evaluation/task-review.jsonl', expectedCount: 10 },
      knowledgeDrift: { path: 'evaluation/knowledge-drift.jsonl', expectedCount: 4 },
    },
  }));
  fs.writeFileSync(path.join(evaluation, 'thresholds.json'), JSON.stringify({
    retrievalRecallAt10: 0.85, citationCoverageRate: 0.95, statementSupportRate: 0.9,
    changeFileRecallRate: 0.85, diffFileExactness: 1, fabricatedPathCount: 0,
    provenanceCompleteness: 1, exactReferenceDriftPrecision: 1, knowledgePrecision: 0.9,
    p95LatencyMs: 5000,
  }));
  return { root, gold: { retrieval, qa, change, taskReview, knowledgeDrift } };
}

function codeSource(id) {
  return {
    id, sourceType: 'CODE_FACT', repositoryId: 'repository', snapshotId: 'snapshot',
    commitSha: null, worktreeDigest: null, detail: 'fixture code fact',
  };
}

function perfectResult(gold) {
  return {
    datasetVersion: '2.0-test', runId: 'perfect-fixture',
    retrieval: gold.retrieval.map(item => ({ id: item.id, latencyMs: 100, returnedPaths: item.expectedPaths })),
    qa: gold.qa.map(item => ({
      id: item.id, latencyMs: 100, claims: [{ text: item.answerKey, cited: true, supported: true }],
      assessor: { type: 'HUMAN', name: 'test-human', assessedAt: '2026-08-30T00:00:00Z' },
    })),
    change: gold.change.map(item => ({ id: item.id, latencyMs: 100, predictedFiles: item.expectedFiles })),
    taskReview: gold.taskReview.map(item => ({
      id: item.id, latencyMs: 100, changedFiles: item.expectedFiles,
      changedSymbols: item.expectedSymbols, knowledgeIds: item.expectedKnowledgeIds,
      requiredTests: item.expectedRequiredTests, staleKnowledgeIds: item.expectedStaleKnowledgeIds,
      findings: [{ id: `${item.id}-finding`, sources: [codeSource(`${item.id}-source`)] }],
      pathChecks: [{ path: 'fixture.txt', existsAtVersion: true }],
    })),
    knowledgeDrift: gold.knowledgeDrift.map(item => ({
      id: item.id, latencyMs: 100,
      driftedKnowledgeIds: item.expectedDrift ? [item.knowledgeId] : [],
      evidence: item.expectedDrift ? [codeSource(`${item.id}-source`)] : [],
    })),
  };
}

function run(root, args) {
  return spawnSync(process.execPath, [evaluator, ...args, '--root', root], { encoding: 'utf8' });
}

function evaluate(root, payload) {
  const file = path.join(root, 'result.json');
  fs.writeFileSync(file, JSON.stringify(payload));
  return run(root, ['--results', file]);
}

test('repository gold validates structurally but cannot be scored before named human review', () => {
  const validation = spawnSync(process.execPath, [evaluator, '--validate'], {
    cwd: repositoryRoot, encoding: 'utf8',
  });
  assert.equal(validation.status, 0, validation.stderr);
  const report = JSON.parse(validation.stdout);
  assert.equal(report.valid, true);
  assert.equal(report.scoreable, false);
  assert.equal(report.counts.taskReview, 10);
  assert.ok(report.pendingAdjudications.length >= 10);
});

test('perfect reviewed run passes every release threshold', () => {
  const fixture = createFixtureRoot();
  try {
    const result = evaluate(fixture.root, perfectResult(fixture.gold));
    assert.equal(result.status, 0, result.stderr);
    const report = JSON.parse(result.stdout);
    assert.equal(report.passed, true);
    assert.equal(report.metrics.diffFileExactness, 1);
    assert.equal(report.metrics.knowledgePrecision, 1);
    assert.equal(report.metrics.driftRecall, 1);
    assert.equal(report.metrics.provenanceCompleteness, 1);
    assert.equal(report.metrics.fabricatedPathCount, 0);
    assert.equal(report.metrics.p95LatencyMs, 100);
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('false positives and false negatives are reported separately and fail the gate', () => {
  const fixture = createFixtureRoot();
  try {
    const payload = perfectResult(fixture.gold);
    payload.taskReview[0].changedFiles = ['invented.txt'];
    payload.taskReview[0].knowledgeIds = ['KN-INVENTED'];
    payload.taskReview[0].pathChecks = [{ path: 'invented.txt', existsAtVersion: false }];
    payload.knowledgeDrift[1].driftedKnowledgeIds = ['KN-FIXTURE'];
    payload.knowledgeDrift[1].evidence = [codeSource('false-positive-source')];
    const result = evaluate(fixture.root, payload);
    assert.equal(result.status, 2, result.stderr);
    const report = JSON.parse(result.stdout);
    assert.equal(report.passed, false);
    assert.equal(report.metrics.fabricatedPathCount, 1);
    assert.ok(report.confusion.knowledge.fp > 0);
    assert.ok(report.confusion.knowledge.fn > 0);
    assert.ok(report.confusion.drift.fp > 0);
    assert.deepEqual(report.errors.taskReview[0].falsePositiveFiles, ['invented.txt']);
    assert.deepEqual(report.errors.taskReview[0].missingFiles, ['fixture.txt']);
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('missing cases cannot be reported as a complete evaluation', () => {
  const fixture = createFixtureRoot();
  try {
    const payload = perfectResult(fixture.gold);
    payload.taskReview.pop();
    const result = evaluate(fixture.root, payload);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /结果不完整/);
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});
