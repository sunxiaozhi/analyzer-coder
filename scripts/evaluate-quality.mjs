import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const resolveInput = (file) => path.isAbsolute(file) ? file : path.join(root, file);
const readJson = (file) => JSON.parse(fs.readFileSync(resolveInput(file), 'utf8'));
const manifest = readJson('evaluation/manifest.json');
const thresholds = readJson('evaluation/thresholds.json');

function fail(message) {
  throw new Error(message);
}

function readJsonl(file) {
  return fs.readFileSync(path.join(root, file), 'utf8')
    .split(/\r?\n/)
    .filter((line) => line.trim())
    .map((line, index) => {
      try { return JSON.parse(line); }
      catch (error) { fail(`${file}:${index + 1} 不是合法 JSON：${error.message}`); }
    });
}

function assertFile(relative, owner) {
  if (typeof relative !== 'string' || !relative || path.isAbsolute(relative) || relative.includes('..')) {
    fail(`${owner} 包含非法仓库路径：${relative}`);
  }
  if (!fs.existsSync(path.join(root, relative))) fail(`${owner} 引用了不存在的文件：${relative}`);
}

function loadGold() {
  const retrieval = readJsonl(manifest.datasets.retrieval.path);
  const qa = readJsonl(manifest.datasets.qa.path);
  const change = readJsonl(manifest.datasets.change.path);
  const groups = { retrieval, qa, change };
  const seen = new Set();
  for (const [name, cases] of Object.entries(groups)) {
    const expected = manifest.datasets[name].expectedCount;
    if (cases.length !== expected) fail(`${name} 应为 ${expected} 条，实际 ${cases.length} 条`);
    for (const item of cases) {
      if (!item.id || seen.has(item.id)) fail(`用例 ID 缺失或重复：${item.id}`);
      seen.add(item.id);
      if (!manifest.repositoryTypes.includes(item.repositoryType)) fail(`${item.id} 仓库类型无效`);
      const files = name === 'retrieval'
        ? item.expectedPaths
        : name === 'qa' ? item.evidence?.map((entry) => entry.path) : item.expectedFiles;
      if (!Array.isArray(files) || files.length === 0) fail(`${item.id} 没有金标准文件`);
      files.forEach((file) => assertFile(file, item.id));
      if (name === 'qa' && (item.curation?.method !== 'HUMAN' || !item.curation?.reviewedAt)) {
        fail(`${item.id} 缺少人工证据审阅信息`);
      }
    }
  }
  for (const type of manifest.repositoryTypes) {
    if (![...seen].length || !Object.values(groups).flat().some((item) => item.repositoryType === type)) {
      fail(`数据集未覆盖仓库类型 ${type}`);
    }
  }
  return groups;
}

function exactResults(expected, actual, name) {
  if (!Array.isArray(actual)) fail(`结果缺少 ${name} 数组`);
  const expectedIds = new Set(expected.map((item) => item.id));
  const actualIds = actual.map((item) => item.id);
  if (new Set(actualIds).size !== actualIds.length) fail(`${name} 结果包含重复 ID`);
  const missing = [...expectedIds].filter((id) => !actualIds.includes(id));
  const unknown = actualIds.filter((id) => !expectedIds.has(id));
  if (missing.length || unknown.length) fail(`${name} 结果不完整；缺失=${missing.join(',')} 未知=${unknown.join(',')}`);
  return new Map(actual.map((item) => [item.id, item]));
}

const ratio = (hit, total) => total ? hit / total : 0;
const normalizedPaths = (values) => new Set((values ?? []).map((value) => value.replaceAll('\\', '/')));
function recall(expected, actual) {
  const returned = normalizedPaths(actual);
  return ratio(expected.filter((file) => returned.has(file)).length, expected.length);
}
function percentile95(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(sorted.length * 0.95) - 1)] ?? Number.POSITIVE_INFINITY;
}

function evaluate(gold, result) {
  if (result.datasetVersion !== manifest.datasetVersion) fail('结果与金标准版本不一致');
  if (!result.runId) fail('结果缺少 runId');
  const retrieval = exactResults(gold.retrieval, result.retrieval, 'retrieval');
  const qa = exactResults(gold.qa, result.qa, 'qa');
  const change = exactResults(gold.change, result.change, 'change');
  const latencies = [];
  let retrievalRecall = 0;
  for (const item of gold.retrieval) {
    const observed = retrieval.get(item.id);
    retrievalRecall += recall(item.expectedPaths, (observed.returnedPaths ?? []).slice(0, 10));
    latencies.push(observed.latencyMs);
  }
  let cited = 0, supported = 0, claims = 0;
  for (const item of gold.qa) {
    const observed = qa.get(item.id);
    if (observed.assessor?.type !== 'HUMAN' || !observed.assessor?.name || !observed.assessor?.assessedAt) {
      fail(`${item.id} 的陈述支持判定必须由具名人工评审`);
    }
    if (!Array.isArray(observed.claims) || observed.claims.length === 0) fail(`${item.id} 没有可评审陈述`);
    for (const claim of observed.claims) {
      if (typeof claim.cited !== 'boolean' || typeof claim.supported !== 'boolean') fail(`${item.id} 陈述判定不完整`);
      claims += 1;
      cited += claim.cited ? 1 : 0;
      supported += claim.supported ? 1 : 0;
    }
    latencies.push(observed.latencyMs);
  }
  let changeRecall = 0;
  for (const item of gold.change) {
    const observed = change.get(item.id);
    changeRecall += recall(item.expectedFiles, observed.predictedFiles ?? []);
    latencies.push(observed.latencyMs);
  }
  if (latencies.some((value) => !Number.isFinite(value) || value < 0)) fail('latencyMs 必须是非负数字');
  const metrics = {
    retrievalRecallAt10: retrievalRecall / gold.retrieval.length,
    citationCoverageRate: ratio(cited, claims),
    statementSupportRate: ratio(supported, claims),
    changeFileRecallRate: changeRecall / gold.change.length,
    p95LatencyMs: percentile95(latencies),
  };
  const checks = Object.entries(thresholds).map(([metric, threshold]) => ({
    metric,
    value: metrics[metric],
    threshold,
    passed: metric === 'p95LatencyMs' ? metrics[metric] <= threshold : metrics[metric] >= threshold,
  }));
  return { datasetVersion: manifest.datasetVersion, runId: result.runId, metrics, thresholds, checks, passed: checks.every((item) => item.passed) };
}

try {
  const gold = loadGold();
  const resultIndex = process.argv.indexOf('--results');
  if (process.argv.includes('--validate')) {
    console.log(JSON.stringify({ valid: true, datasetVersion: manifest.datasetVersion, counts: Object.fromEntries(Object.entries(gold).map(([key, value]) => [key, value.length])) }, null, 2));
  } else if (resultIndex >= 0 && process.argv[resultIndex + 1]) {
    const report = evaluate(gold, readJson(process.argv[resultIndex + 1]));
    console.log(JSON.stringify(report, null, 2));
    if (!report.passed) process.exitCode = 2;
  } else {
    fail('请使用 --validate 或 --results <实际评测结果.json>');
  }
} catch (error) {
  console.error(`评测失败：${error.message}`);
  process.exitCode = 1;
}
