import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const GIT_OBJECT_PATTERN = /^[0-9a-f]{40,64}$/i;
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

function required(env, name) {
  const value = env[name]?.trim();
  if (!value) throw new Error(`缺少环境变量 ${name}`);
  return value;
}

function identifier(value, name) {
  if (!UUID_PATTERN.test(value)) throw new Error(`${name} 必须是 UUID`);
  return value.toLowerCase();
}

function credential(value, name) {
  if (value.length > 4096 || !/^[A-Za-z0-9._~-]+$/.test(value)) {
    throw new Error(`${name} 格式无效`);
  }
  return value;
}

function baseUrl(value) {
  let url;
  try { url = new URL(value); }
  catch { throw new Error('ANALYZER_BASE_URL 不是合法 URL'); }
  if (url.username || url.password || url.search || url.hash) {
    throw new Error('ANALYZER_BASE_URL 不能包含凭据、查询参数或片段');
  }
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && LOCAL_HOSTS.has(url.hostname))) {
    throw new Error('远程 Analyzer 必须使用 HTTPS；HTTP 仅允许本机回环地址');
  }
  return url.toString().replace(/\/$/, '');
}

function reports(file, label) {
  if (!file?.trim()) return [];
  const resolved = path.resolve(file.trim());
  let value;
  try { value = JSON.parse(fs.readFileSync(resolved, 'utf8')); }
  catch (error) { throw new Error(`${label}读取失败：${error.message}`); }
  if (!Array.isArray(value)) throw new Error(`${label}必须是 JSON 数组`);
  return value;
}

export function configFromEnv(env) {
  const headCommit = required(env, 'ANALYZER_HEAD_COMMIT').toLowerCase();
  if (!GIT_OBJECT_PATTERN.test(headCommit)) {
    throw new Error('ANALYZER_HEAD_COMMIT 必须是完整 Git 对象 ID');
  }
  return {
    baseUrl: baseUrl(env.ANALYZER_BASE_URL?.trim() || 'http://127.0.0.1:8080'),
    repositoryId: identifier(required(env, 'ANALYZER_REPOSITORY_ID'), 'ANALYZER_REPOSITORY_ID'),
    reviewId: identifier(required(env, 'ANALYZER_REVIEW_ID'), 'ANALYZER_REVIEW_ID'),
    headCommit,
    session: credential(required(env, 'ANALYZER_SESSION_COOKIE'), 'ANALYZER_SESSION_COOKIE'),
    csrf: credential(required(env, 'ANALYZER_CSRF_TOKEN'), 'ANALYZER_CSRF_TOKEN'),
    tests: reports(env.ANALYZER_TEST_REPORTS_FILE, '测试回报'),
    approvals: reports(env.ANALYZER_APPROVAL_REPORTS_FILE, '审批回报'),
  };
}

function safeJson(response) {
  return response.json().catch(() => null);
}

export function formatResult(result) {
  const lines = [
    `Analyzer CI: ${result.decision} (${result.policyVersion})`,
    `review=${result.reviewId} head=${result.headCommit}`,
  ];
  for (const finding of result.blockingFindings ?? []) {
    lines.push(`[BLOCK] ${finding.code}: ${finding.title} — ${finding.detail}`);
  }
  for (const finding of result.advisories ?? []) {
    lines.push(`[INFO] ${finding.code}: ${finding.title} — ${finding.detail}`);
  }
  return lines.join('\n');
}

function validResult(value) {
  return value
    && ['PASS', 'FAIL'].includes(value.decision)
    && [0, 1].includes(value.exitCode)
    && value.exitCode === (value.decision === 'PASS' ? 0 : 1)
    && typeof value.policyVersion === 'string'
    && Array.isArray(value.blockingFindings)
    && Array.isArray(value.advisories);
}

export async function run(env = process.env, fetchImpl = globalThis.fetch, output = console) {
  let config;
  try { config = configFromEnv(env); }
  catch (error) {
    output.error(`Analyzer CI 配置错误：${error.message}`);
    return 2;
  }
  const endpoint = `${config.baseUrl}/api/repositories/${config.repositoryId}/task-reviews/${config.reviewId}/ci-check`;
  let response;
  try {
    response = await fetchImpl(endpoint, {
      method: 'POST',
      redirect: 'error',
      signal: AbortSignal.timeout(30_000),
      headers: {
        'Content-Type': 'application/json',
        Cookie: `AC_SESSION=${config.session}`,
        'X-CSRF-Token': config.csrf,
      },
      body: JSON.stringify({
        headCommit: config.headCommit,
        tests: config.tests,
        approvals: config.approvals,
      }),
    });
  } catch (error) {
    output.error(`Analyzer CI 请求失败：${error.message}`);
    return 2;
  }
  const payload = await safeJson(response);
  if (!response.ok) {
    output.error(`Analyzer CI 接口错误：${payload?.code ?? `HTTP_${response.status}`} ${payload?.message ?? response.statusText}`);
    return 2;
  }
  if (!validResult(payload)) {
    output.error('Analyzer CI 返回了不兼容的判定结果');
    return 2;
  }
  output.log(formatResult(payload));
  return payload.exitCode;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : null;
if (invokedPath === fileURLToPath(import.meta.url)) {
  process.exitCode = await run();
}
