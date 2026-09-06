import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';

if (process.argv.includes('--help')) {
  console.log(`Usage: node scripts/check-runtime.mjs [--json]
Read-only runtime checks; no credentials are printed and no services are started.
Uses APP_DATASOURCE_URL, APP_SERVER_PORT, APP_CODEGRAPH_EXECUTABLE,
APP_REPOSITORY_ALLOWED_ROOTS and APP_MANAGED_DATA_ROOT from the current environment.
Exit 0: core checks pass. Exit 1: core runtime is incomplete.
CodeGraph is optional for local evidence search, required for graph analysis.`);
  process.exit(0);
}

function executableExists(command) {
  const extensions = process.platform === 'win32' ? ['', ...(process.env.PATHEXT ?? '.EXE;.CMD;.BAT').split(';')] : [''];
  const directories = command.includes('/') || command.includes('\\')
    ? [''] : (process.env.PATH ?? '').split(path.delimiter);
  return directories.some(directory => extensions.some(extension => {
    try {
      const candidate = path.join(directory, command + extension);
      fs.accessSync(candidate, fs.constants.X_OK);
      return fs.statSync(candidate).isFile();
    } catch { return false; }
  }));
}

function reachable(host, port) {
  return new Promise(resolve => {
    const socket = net.createConnection({ host, port });
    const finish = success => { socket.destroy(); resolve(success); };
    socket.setTimeout(2500, () => finish(false));
    socket.once('connect', () => finish(true));
    socket.once('error', () => finish(false));
  });
}

const checks = [];
function record(name, passed, required, detail) {
  checks.push({ name, status: passed ? 'PASS' : required ? 'FAIL' : 'WARN', detail });
}
record('Node.js', Number(process.versions.node.split('.')[0]) >= 20, true, '需要 Node.js 20 或以上');
for (const [name, required] of [['java', true], ['mvn', true], ['git', true], [process.env.APP_CODEGRAPH_EXECUTABLE || 'codegraph', false]]) {
  record(name === (process.env.APP_CODEGRAPH_EXECUTABLE || 'codegraph') ? 'CodeGraph' : name,
    executableExists(name), required, required ? '源码构建/仓库接入所需命令' : '缺少 CLI 时，调用图谱阶段失败；源码检索仍可用');
}

let database;
try { database = new URL((process.env.APP_DATASOURCE_URL || 'jdbc:postgresql://127.0.0.1:5432/codebase_kb').replace(/^jdbc:/, '')); }
catch { /* Report configuration failure without printing a connection string. */ }
record('PostgreSQL TCP', database ? await reachable(database.hostname.replace(/^\[|\]$/g, ''), Number(database.port || 5432)) : false,
  true, '仅验证端口可达；账号认证、Flyway 与 pgvector 由后端健康检查和集成测试验证');

let healthy = false;
try {
  const response = await fetch(`http://127.0.0.1:${Number(process.env.APP_SERVER_PORT || 8080)}/actuator/health`, { signal: AbortSignal.timeout(3000) });
  healthy = response.ok && (await response.json()).status === 'UP';
} catch { /* A stopped backend is an actionable diagnostic, not a tool crash. */ }
record('Backend health', healthy, true, '后端 /actuator/health 必须返回 UP');

const roots = (process.env.APP_REPOSITORY_ALLOWED_ROOTS || '').split(/[,;]/).map(value => value.trim()).filter(Boolean);
record('Repository roots', roots.length > 0 && roots.every(root => {
  try { return fs.statSync(root).isDirectory(); } catch { return false; }
}), true, '当前终端需设置 APP_REPOSITORY_ALLOWED_ROOTS，目录必须存在');
record('Managed storage', Boolean(process.env.APP_MANAGED_DATA_ROOT), true, '当前终端需设置独立的 APP_MANAGED_DATA_ROOT');

if (process.argv.includes('--json')) console.log(JSON.stringify({ ready: !checks.some(item => item.status === 'FAIL'), checks }, null, 2));
else for (const item of checks) console.log(`[${item.status}] ${item.name}: ${item.detail}`);
process.exitCode = checks.some(item => item.status === 'FAIL') ? 1 : 0;
