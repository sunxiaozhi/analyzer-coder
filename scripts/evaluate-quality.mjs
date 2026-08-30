import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const defaultRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const rootIndex = process.argv.indexOf('--root');
const root = rootIndex >= 0 && process.argv[rootIndex + 1]
  ? path.resolve(process.argv[rootIndex + 1])
  : defaultRoot;
const resolveInput = file => path.isAbsolute(file) ? file : path.join(root, file);
const readJson = file => JSON.parse(fs.readFileSync(resolveInput(file), 'utf8'));
const manifest = readJson('evaluation/manifest.json');
const thresholds = readJson('evaluation/thresholds.json');

function fail(message) {
  throw new Error(message);
}

function readJsonl(file) {
  return fs.readFileSync(resolveInput(file), 'utf8')
    .split(/\r?\n/)
    .filter(line => line.trim())
    .map((line, index) => {
      try { return JSON.parse(line); }
      catch (error) { fail(`${file}:${index + 1} 不是合法 JSON：${error.message}`); }
    });
}

function requireArray(value, owner, field, { nonEmpty = false } = {}) {
  if (!Array.isArray(value) || (nonEmpty && value.length === 0)) {
    fail(`${owner}.${field} 必须是${nonEmpty ? '非空' : ''}数组`);
  }
  return value;
}

function assertFile(relative, owner) {
  if (typeof relative !== 'string' || !relative || path.isAbsolute(relative) || relative.includes('..')) {
    fail(`${owner} 包含非法仓库路径：${relative}`);
  }
  if (!fs.existsSync(path.join(root, relative))) fail(`${owner} 引用了不存在的文件：${relative}`);
}

function validateAdjudication(item, pending) {
  const adjudication = item.adjudication;
  if (!adjudication || !['REVIEWED', 'PENDING_HUMAN_REVIEW'].includes(adjudication.status)) {
    fail(`${item.id} 缺少有效的人工判断状态`);
  }
  if (adjudication.status === 'PENDING_HUMAN_REVIEW') {
    pending.push(item.id);
    return;
  }
  if (typeof adjudication.reviewer !== 'string' || !adjudication.reviewer.trim()) {
    fail(`${item.id} 已评审但缺少评审人`);
  }
  if (!adjudication.reviewedAt || Number.isNaN(Date.parse(adjudication.reviewedAt))) {
    fail(`${item.id} 已评审但缺少合法评审时间`);
  }
}

function validateGoldItem(name, item, pending) {
  if (!manifest.repositoryTypes.includes(item.repositoryType)) fail(`${item.id} 仓库类型无效`);
  if (name === 'retrieval') {
    requireArray(item.expectedPaths, item.id, 'expectedPaths', { nonEmpty: true })
      .forEach(file => assertFile(file, item.id));
    return;
  }
  if (name === 'qa') {
    requireArray(item.evidence, item.id, 'evidence', { nonEmpty: true })
      .forEach(entry => assertFile(entry.path, item.id));
    if (item.curation?.method !== 'HUMAN' || !item.curation?.reviewedAt) {
      fail(`${item.id} 缺少人工证据审阅信息`);
    }
    return;
  }
  if (name === 'change') {
    requireArray(item.expectedFiles, item.id, 'expectedFiles', { nonEmpty: true })
      .forEach(file => assertFile(file, item.id));
    return;
  }
  if (name === 'taskReview') {
    requireArray(item.expectedFiles, item.id, 'expectedFiles', { nonEmpty: true })
      .forEach(file => assertFile(file, item.id));
    requireArray(item.expectedSymbols, item.id, 'expectedSymbols', { nonEmpty: true });
    requireArray(item.expectedKnowledgeIds, item.id, 'expectedKnowledgeIds');
    requireArray(item.expectedRequiredTests, item.id, 'expectedRequiredTests');
    requireArray(item.expectedStaleKnowledgeIds, item.id, 'expectedStaleKnowledgeIds');
    validateAdjudication(item, pending);
    return;
  }
  if (name === 'knowledgeDrift') {
    requireArray(item.changedFiles, item.id, 'changedFiles', { nonEmpty: true })
      .forEach(file => assertFile(file, item.id));
    if (!['EXACT_REFERENCE', 'PATH_SCOPE', 'SYMBOL_SCOPE'].includes(item.ruleType)) {
      fail(`${item.id}.ruleType 无效`);
    }
    if (typeof item.knowledgeId !== 'string' || !item.knowledgeId) fail(`${item.id} 缺少知识标识`);
    if (typeof item.expectedDrift !== 'boolean') fail(`${item.id}.expectedDrift 必须是布尔值`);
    validateAdjudication(item, pending);
  }
}

function loadGold() {
  const groups = {};
  const seen = new Set();
  const pendingAdjudications = [];
  for (const [name, definition] of Object.entries(manifest.datasets)) {
    const cases = readJsonl(definition.path);
    if (cases.length !== definition.expectedCount) {
      fail(`${name} 应为 ${definition.expectedCount} 条，实际 ${cases.length} 条`);
    }
    groups[name] = cases;
    for (const item of cases) {
      if (!item.id || seen.has(item.id)) fail(`用例 ID 缺失或重复：${item.id}`);
      seen.add(item.id);
      validateGoldItem(name, item, pendingAdjudications);
    }
  }
  for (const type of manifest.repositoryTypes) {
    if (!Object.values(groups).flat().some(item => item.repositoryType === type)) {
      fail(`数据集未覆盖仓库类型 ${type}`);
    }
  }
  if ((groups.taskReview?.length ?? 0) < 10) fail('任务审查金标准至少需要 10 个真实开发任务');
  return { groups, pendingAdjudications };
}

function exactResults(expected, actual, name) {
  if (!Array.isArray(actual)) fail(`结果缺少 ${name} 数组`);
  const expectedIds = new Set(expected.map(item => item.id));
  const actualIds = actual.map(item => item.id);
  if (new Set(actualIds).size !== actualIds.length) fail(`${name} 结果包含重复 ID`);
  const missing = [...expectedIds].filter(id => !actualIds.includes(id));
  const unknown = actualIds.filter(id => !expectedIds.has(id));
  if (missing.length || unknown.length) {
    fail(`${name} 结果不完整；缺失=${missing.join(',')} 未知=${unknown.join(',')}`);
  }
  return new Map(actual.map(item => [item.id, item]));
}

const ratio = (hit, total, empty = 0) => total ? hit / total : empty;
const normalizedPaths = values => new Set((values ?? []).map(value => value.replaceAll('\\', '/')));
const normalizedValues = values => new Set((values ?? []).map(value => String(value).trim()).filter(Boolean));

function recall(expected, actual) {
  const returned = normalizedPaths(actual);
  return ratio(expected.filter(file => returned.has(file)).length, expected.length);
}

function exactSet(expected, actual, paths = false) {
  const left = paths ? normalizedPaths(expected) : normalizedValues(expected);
  const right = paths ? normalizedPaths(actual) : normalizedValues(actual);
  return left.size === right.size && [...left].every(value => right.has(value));
}

function percentile95(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(sorted.length * 0.95) - 1)] ?? Number.POSITIVE_INFINITY;
}

function tally(expectedValues, actualValues, target) {
  const expected = normalizedValues(expectedValues);
  const actual = normalizedValues(actualValues);
  target.tp += [...actual].filter(value => expected.has(value)).length;
  target.fp += [...actual].filter(value => !expected.has(value)).length;
  target.fn += [...expected].filter(value => !actual.has(value)).length;
}

function precision(stats) {
  return ratio(stats.tp, stats.tp + stats.fp, stats.fn === 0 ? 1 : 0);
}

function measuredRecall(stats) {
  return ratio(stats.tp, stats.tp + stats.fn, stats.fp === 0 ? 1 : 0);
}

function completeSource(source, findingIds) {
  if (!source || typeof source.id !== 'string' || !source.id || typeof source.detail !== 'string') return false;
  const versioned = Boolean(source.snapshotId || source.commitSha || source.worktreeDigest);
  if (['GIT_FACT', 'CODE_FACT'].includes(source.sourceType)) return versioned;
  if (source.sourceType === 'VERIFIED_KNOWLEDGE') {
    return Boolean(source.knowledgeCardId && Number.isInteger(source.knowledgeRevision)
      && source.knowledgeRevision > 0 && source.knowledgeReviewStatus);
  }
  if (source.sourceType === 'GRAPH_INFERENCE') {
    return versioned && Boolean(source.graphArtifactId)
      && Array.isArray(source.relationPath) && source.relationPath.length > 0;
  }
  if (source.sourceType === 'RETRIEVAL_CANDIDATE') return Boolean(source.retrievalChannel);
  if (source.sourceType === 'MODEL_SUGGESTION') return findingIds.has(source.findingId);
  return source.sourceType === 'UNKNOWN';
}

function evaluate(goldState, result) {
  const { groups: gold, pendingAdjudications } = goldState;
  if (pendingAdjudications.length) {
    fail(`金标准仍有 ${pendingAdjudications.length} 条待人工复核：${pendingAdjudications.join(',')}`);
  }
  if (result.datasetVersion !== manifest.datasetVersion) fail('结果与金标准版本不一致');
  if (!result.runId) fail('结果缺少 runId');
  const retrieval = exactResults(gold.retrieval, result.retrieval, 'retrieval');
  const qa = exactResults(gold.qa, result.qa, 'qa');
  const change = exactResults(gold.change, result.change, 'change');
  const taskReview = exactResults(gold.taskReview, result.taskReview, 'taskReview');
  const knowledgeDrift = exactResults(gold.knowledgeDrift, result.knowledgeDrift, 'knowledgeDrift');
  const latencies = [];
  const errors = { taskReview: [], knowledgeDrift: [] };

  let retrievalRecall = 0;
  for (const item of gold.retrieval) {
    const observed = retrieval.get(item.id);
    retrievalRecall += recall(item.expectedPaths, requireArray(observed.returnedPaths, item.id, 'returnedPaths').slice(0, 10));
    latencies.push(observed.latencyMs);
  }

  let cited = 0;
  let supported = 0;
  let claims = 0;
  for (const item of gold.qa) {
    const observed = qa.get(item.id);
    if (observed.assessor?.type !== 'HUMAN' || !observed.assessor?.name || !observed.assessor?.assessedAt) {
      fail(`${item.id} 的陈述支持判定必须由具名人工评审`);
    }
    requireArray(observed.claims, item.id, 'claims', { nonEmpty: true });
    for (const claim of observed.claims) {
      if (typeof claim.cited !== 'boolean' || typeof claim.supported !== 'boolean') {
        fail(`${item.id} 陈述判定不完整`);
      }
      claims += 1;
      cited += claim.cited ? 1 : 0;
      supported += claim.supported ? 1 : 0;
    }
    latencies.push(observed.latencyMs);
  }

  let changeRecall = 0;
  for (const item of gold.change) {
    const observed = change.get(item.id);
    changeRecall += recall(item.expectedFiles, requireArray(observed.predictedFiles, item.id, 'predictedFiles'));
    latencies.push(observed.latencyMs);
  }

  let exactFiles = 0;
  let fabricatedPathCount = 0;
  let provenanceComplete = 0;
  let provenanceTotal = 0;
  const symbolStats = { tp: 0, fp: 0, fn: 0 };
  const knowledgeStats = { tp: 0, fp: 0, fn: 0 };
  for (const item of gold.taskReview) {
    const observed = taskReview.get(item.id);
    const changedFiles = requireArray(observed.changedFiles, item.id, 'changedFiles');
    const changedSymbols = requireArray(observed.changedSymbols, item.id, 'changedSymbols');
    const knowledgeIds = requireArray(observed.knowledgeIds, item.id, 'knowledgeIds');
    const staleKnowledgeIds = requireArray(observed.staleKnowledgeIds, item.id, 'staleKnowledgeIds');
    requireArray(observed.requiredTests, item.id, 'requiredTests');
    const findings = requireArray(observed.findings, item.id, 'findings');
    const pathChecks = requireArray(observed.pathChecks, item.id, 'pathChecks');
    if (exactSet(item.expectedFiles, changedFiles, true)) exactFiles += 1;
    tally(item.expectedSymbols, changedSymbols, symbolStats);
    tally(
      [...item.expectedKnowledgeIds, ...item.expectedStaleKnowledgeIds],
      [...knowledgeIds, ...staleKnowledgeIds],
      knowledgeStats,
    );
    const findingIds = new Set(findings.map(finding => finding.id).filter(Boolean));
    const claimedPaths = new Set(changedFiles.map(value => value.replaceAll('\\', '/')));
    for (const finding of findings) {
      provenanceTotal += 1;
      if (Array.isArray(finding.sources) && finding.sources.length > 0
        && finding.sources.every(source => completeSource(source, findingIds))) {
        provenanceComplete += 1;
      }
      for (const source of finding.sources ?? []) {
        if (typeof source.filePath === 'string' && source.filePath
          && !source.filePath.startsWith('knowledge://')) {
          claimedPaths.add(source.filePath.replaceAll('\\', '/'));
        }
      }
    }
    const checkedPaths = new Set();
    for (const check of pathChecks) {
      if (typeof check.path !== 'string' || typeof check.existsAtVersion !== 'boolean') {
        fail(`${item.id}.pathChecks 判定不完整`);
      }
      checkedPaths.add(check.path.replaceAll('\\', '/'));
      if (!check.existsAtVersion) fabricatedPathCount += 1;
    }
    const unchecked = [...claimedPaths].filter(claimed => !checkedPaths.has(claimed));
    if (unchecked.length) fail(`${item.id} 缺少路径真实性判定：${unchecked.join(',')}`);
    const actualFiles = normalizedPaths(changedFiles);
    const expectedFiles = normalizedPaths(item.expectedFiles);
    const falsePositiveFiles = [...actualFiles].filter(file => !expectedFiles.has(file));
    const missingFiles = [...expectedFiles].filter(file => !actualFiles.has(file));
    if (falsePositiveFiles.length || missingFiles.length) {
      errors.taskReview.push({ id: item.id, falsePositiveFiles, missingFiles });
    }
    latencies.push(observed.latencyMs);
  }

  const driftStats = { tp: 0, fp: 0, fn: 0 };
  const exactReferenceStats = { tp: 0, fp: 0, fn: 0 };
  for (const item of gold.knowledgeDrift) {
    const observed = knowledgeDrift.get(item.id);
    const actual = requireArray(observed.driftedKnowledgeIds, item.id, 'driftedKnowledgeIds');
    const expected = item.expectedDrift ? [item.knowledgeId] : [];
    tally(expected, actual, driftStats);
    if (item.ruleType === 'EXACT_REFERENCE') tally(expected, actual, exactReferenceStats);
    const expectedSet = normalizedValues(expected);
    const actualSet = normalizedValues(actual);
    const falsePositives = [...actualSet].filter(value => !expectedSet.has(value));
    const falseNegatives = [...expectedSet].filter(value => !actualSet.has(value));
    if (falsePositives.length || falseNegatives.length) {
      errors.knowledgeDrift.push({ id: item.id, falsePositives, falseNegatives });
    }
    if (actual.length) {
      const evidence = requireArray(observed.evidence, item.id, 'evidence', { nonEmpty: true });
      provenanceTotal += 1;
      if (evidence.every(source => completeSource(source, new Set()))) provenanceComplete += 1;
    } else {
      requireArray(observed.evidence, item.id, 'evidence');
    }
    latencies.push(observed.latencyMs);
  }

  if (latencies.some(value => !Number.isFinite(value) || value < 0)) fail('latencyMs 必须是非负数字');
  const metrics = {
    retrievalRecallAt10: retrievalRecall / gold.retrieval.length,
    citationCoverageRate: ratio(cited, claims),
    statementSupportRate: ratio(supported, claims),
    changeFileRecallRate: changeRecall / gold.change.length,
    diffFileExactness: exactFiles / gold.taskReview.length,
    symbolPrecision: precision(symbolStats),
    symbolRecall: measuredRecall(symbolStats),
    knowledgePrecision: precision(knowledgeStats),
    knowledgeRecall: measuredRecall(knowledgeStats),
    driftPrecision: precision(driftStats),
    driftRecall: measuredRecall(driftStats),
    exactReferenceDriftPrecision: precision(exactReferenceStats),
    provenanceCompleteness: ratio(provenanceComplete, provenanceTotal),
    fabricatedPathCount,
    p95LatencyMs: percentile95(latencies),
  };
  const checks = Object.entries(thresholds).map(([metric, threshold]) => {
    if (!(metric in metrics)) fail(`门槛引用未知指标：${metric}`);
    const lowerIsBetter = ['p95LatencyMs', 'fabricatedPathCount'].includes(metric);
    return {
      metric,
      value: metrics[metric],
      threshold,
      passed: lowerIsBetter ? metrics[metric] <= threshold : metrics[metric] >= threshold,
    };
  });
  return {
    datasetVersion: manifest.datasetVersion,
    runId: result.runId,
    measuredAt: new Date().toISOString(),
    metrics,
    confusion: { symbols: symbolStats, knowledge: knowledgeStats, drift: driftStats, exactReferenceDrift: exactReferenceStats },
    errors,
    thresholds,
    checks,
    passed: checks.every(item => item.passed),
  };
}

try {
  const gold = loadGold();
  const resultIndex = process.argv.indexOf('--results');
  if (process.argv.includes('--validate')) {
    console.log(JSON.stringify({
      valid: true,
      scoreable: gold.pendingAdjudications.length === 0,
      datasetVersion: manifest.datasetVersion,
      counts: Object.fromEntries(Object.entries(gold.groups).map(([key, value]) => [key, value.length])),
      pendingAdjudications: gold.pendingAdjudications,
    }, null, 2));
  } else if (resultIndex >= 0 && process.argv[resultIndex + 1]) {
    const report = evaluate(gold, readJson(process.argv[resultIndex + 1]));
    console.log(JSON.stringify(report, null, 2));
    if (!report.passed) process.exitCode = 2;
  } else {
    fail('请使用 --validate 或 --results <实际评测结果.json>，可选 --root <评测根目录>');
  }
} catch (error) {
  console.error(`评测失败：${error.message}`);
  process.exitCode = 1;
}
