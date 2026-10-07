import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const image = process.argv[2] || 'analyzer-coder:1.0.0';
const suffix = randomBytes(8).toString('hex');
const name = `analyzer-smoke-${suffix}`;
const volume = `${name}-data`;
const password = `Smoke-${suffix}-9!`;
function docker(args) {
  const result = spawnSync('docker', args, { encoding: 'utf8', windowsHide: true, timeout: 180000 });
  if (result.error || result.status !== 0) throw new Error(`docker ${args[0]} failed: ${result.error?.message ?? result.stderr}`);
  return result.stdout.trim();
}
async function healthy() {
  for (let attempt = 0; attempt < 120; attempt++) {
    const state = JSON.parse(docker(['inspect', name, '--format', '{{json .State}}']));
    if (!state.Running) throw new Error(`Container exited (${state.ExitCode}).`);
    if (state.Health?.Status === 'healthy') return;
    await delay(2000);
  }
  throw new Error('Container health timed out.');
}
let volumeCreated = false;
let containerCreated = false;
try {
  docker(['volume', 'create', volume]);
  volumeCreated = true;
  docker(['run', '-d', '--name', name, '--stop-timeout', '60', '-p', '127.0.0.1::8080',
    '-v', `${volume}:/data`,
    '-e', `POSTGRES_PASSWORD=${randomBytes(24).toString('hex')}`,
    '-e', 'APP_INITIAL_ADMIN_USERNAME=smoke-admin', '-e', `APP_INITIAL_ADMIN_PASSWORD=${password}`,
    '-e', `APP_LLM_MASTER_KEY=${randomBytes(32).toString('hex')}`,
    '-e', `APP_CREDENTIAL_MASTER_KEY=${randomBytes(32).toString('hex')}`, image]);
  containerCreated = true;
  await healthy();
  const ports = JSON.parse(docker(['inspect', name, '--format', '{{json .NetworkSettings.Ports}}']));
  const base = `http://127.0.0.1:${ports['8080/tcp'][0].HostPort}`;
  const frontend = await fetch(`${base}/`);
  assert.equal(frontend.status, 200);
  assert.match(await frontend.text(), /<html/);
  assert.equal((await fetch(`${base}/assets/missing.js`)).status, 404);
  assert.equal((await fetch(`${base}/actuator/health`)).status, 404);
  const login = await fetch(`${base}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'smoke-admin', password }),
  });
  assert.equal(login.status, 200);
  const account = await login.json();
  assert.equal(account.username, 'smoke-admin');
  const cookie = login.headers.get('set-cookie')?.split(';')[0];
  assert.ok(cookie);
  const exec = args => docker(['exec', '-u', 'analyzer', name, ...args]);
  assert.match(exec(['git', '--version']), /git version/);
  assert.match(exec(['codegraph', '--version']), /1\.6\.0/);
  exec(['node', '--input-type=module', '-e',
    'await import("/opt/analyzer-coder/mcp-server/src/server.mjs"); console.log("MCP imports OK")']);
  const sample = "export function greet(name) { return name; }\nexport function run() { return greet('world'); }\n";
  exec(['node', '-e', `const fs=require('node:fs'); fs.mkdirSync('/data/managed/smoke',{recursive:true}); fs.writeFileSync('/data/managed/smoke/sample.js',${JSON.stringify(sample)});`]);
  exec(['codegraph', 'init', '/data/managed/smoke']);
  exec(['node', '-e', 'require("node:fs").accessSync("/data/managed/smoke/.codegraph/codegraph.db")']);
  const sql = statement => docker(['exec', '-u', 'postgres', name, 'psql', '-U', 'codebase_kb', '-d', 'codebase_kb', '-Atc', statement]);
  assert.equal(sql("SELECT extname FROM pg_extension WHERE extname='vector'"), 'vector');
  sql('CREATE TABLE docker_smoke_marker (value text); INSERT INTO docker_smoke_marker VALUES (\'persisted\');');
  docker(['stop', '--time', '60', name]);
  const stopped = JSON.parse(docker(['inspect', name, '--format', '{{json .State}}']));
  assert.equal(stopped.ExitCode, 0, 'Shutdown should finish without being force killed.');
  docker(['start', name]);
  await healthy();
  assert.equal(sql('SELECT value FROM docker_smoke_marker'), 'persisted');
  exec(['node', '-e', 'require("node:fs").accessSync("/data/managed/smoke/.codegraph/codegraph.db")']);
  const restartedPorts = JSON.parse(docker(['inspect', name, '--format', '{{json .NetworkSettings.Ports}}']));
  const me = await fetch(`http://127.0.0.1:${restartedPorts['8080/tcp'][0].HostPort}/api/auth/me`, { headers: { Cookie: cookie } });
  assert.equal(me.status, 200);
  assert.equal((await me.json()).id, account.id);
  console.log('PASS: frontend, login, pgvector, Git, CodeGraph indexing, MCP dependencies, graceful shutdown and persistent database/files/session.');
} catch (error) {
  if (containerCreated) {
    try { console.error(docker(['logs', '--tail', '100', name])); } catch { /* Preserve original failure. */ }
  }
  console.error(error.message);
  process.exitCode = 1;
} finally {
  if (containerCreated) {
    docker(['stop', '--time', '60', name]);
    docker(['rm', '--volumes', name]);
  }
  if (volumeCreated) docker(['volume', 'rm', volume]);
}
