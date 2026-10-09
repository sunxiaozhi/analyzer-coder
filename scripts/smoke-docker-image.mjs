import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
fs.mkdirSync(path.join(root, '.runtime'), { recursive: true });
const directory = fs.mkdtempSync(path.join(root, '.runtime', 'docker-smoke-'));
const image = process.argv[2] || 'analyzer-coder:1.0.0';
const replacementImage = process.argv[3] || image;
const name = `analyzer-smoke-${randomBytes(8).toString('hex')}`;
const username = 'smoke-admin-from-yaml';
let created = false;
let succeeded = false;
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
function start(target) {
  created = true;
  docker(['run', '-d', '--name', name, '--stop-timeout', '60', '-p', '127.0.0.1::8080',
    '--env-file', path.join(directory, '.env'),
    '-v', `${path.join(directory, 'data')}:/data`,
    '-v', `${path.join(directory, 'config')}:/config:ro`, target]);
}
function stopAndRemove() {
  docker(['stop', '--time', '60', name]);
  const state = JSON.parse(docker(['inspect', name, '--format', '{{json .State}}']));
  assert.equal(state.ExitCode, 0, 'Shutdown must finish without force killing services.');
  docker(['rm', name]);
  created = false;
}
function baseUrl() {
  const ports = JSON.parse(docker(['inspect', name, '--format', '{{json .NetworkSettings.Ports}}']));
  return `http://127.0.0.1:${ports['8080/tcp'][0].HostPort}`;
}
try {
  const user = process.platform === 'win32' ? [] : ['--user', `${process.getuid()}:${process.getgid()}`];
  docker(['run', '--rm', ...user, '--entrypoint', 'node', '-v', `${directory}:/installation`, image,
    '/opt/analyzer-coder/deploy/configure.mjs', '/installation', image]);
  const originalEnv = fs.readFileSync(path.join(directory, '.env'), 'utf8');
  const password = originalEnv.match(/^APP_INITIAL_ADMIN_PASSWORD=(.*)$/m)[1].trim();
  // Prove all three external configuration files affect the running services.
  fs.writeFileSync(path.join(directory, 'config/application.yml'), `app:\n  security:\n    initial-admin-username: ${username}\n`);
  fs.writeFileSync(path.join(directory, 'config/postgresql.conf'), 'max_connections = 73\nshared_buffers = 128MB\n');
  const nginxFile = path.join(directory, 'config/nginx.conf');
  fs.writeFileSync(nginxFile, fs.readFileSync(nginxFile, 'utf8').replace('    location / {',
    '    location = /external-config-test { return 200 "external-config-loaded"; }\n\n    location / {'));
  const configBefore = ['application.yml','postgresql.conf','nginx.conf'].map(file => fs.readFileSync(path.join(directory, 'config', file), 'utf8'));
  start(image);
  await healthy();
  const base = baseUrl();
  assert.equal((await fetch(`${base}/`)).status, 200);
  assert.equal((await fetch(`${base}/assets/missing.js`)).status, 404);
  assert.equal((await fetch(`${base}/actuator/health`)).status, 404);
  assert.equal(await (await fetch(`${base}/external-config-test`)).text(), 'external-config-loaded');
  const login = await fetch(`${base}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  assert.equal(login.status, 200);
  const account = await login.json();
  assert.equal(account.username, username);
  const cookie = login.headers.get('set-cookie')?.split(';')[0];
  assert.ok(cookie);
  const exec = args => docker(['exec', '-u', 'analyzer', name, ...args]);
  assert.match(exec(['git', '--version']), /git version/);
  assert.match(exec(['codegraph', '--version']), /1\.6\.0/);
  const dependencies = JSON.parse(exec(['npm','list','--global','--depth=0','--json'])).dependencies;
  const arch = exec(['node','-p','process.arch']);
  assert.equal(dependencies[`@colbymchenry/codegraph-linux-${arch}`]?.version, '1.6.0');
  exec(['node', '--input-type=module', '-e', 'await import("/opt/analyzer-coder/mcp-server/src/server.mjs");']);
  exec(['node', '-e', `const fs=require('node:fs'); fs.mkdirSync('/data/managed/smoke',{recursive:true}); fs.writeFileSync('/data/managed/smoke/sample.js',${JSON.stringify("export function greet(name) { return name; }\nexport function run() { return greet('world'); }\n")});`]);
  exec(['codegraph','init','/data/managed/smoke']);
  assert.ok(fs.existsSync(path.join(directory, 'data/managed/smoke/.codegraph/codegraph.db')), 'Graph files must be in the host data directory.');
  const sql = statement => docker(['exec','-u','postgres',name,'psql','-U','codebase_kb','-d','codebase_kb','-Atc',statement]);
  assert.equal(sql("SELECT extname FROM pg_extension WHERE extname='vector'"), 'vector');
  assert.equal(sql('SHOW max_connections'), '73');
  // A fresh installation must use the consolidated baseline and include shared knowledge.
  assert.equal(sql("SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success"), '1');
  assert.equal(sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('knowledge_branch_scopes','knowledge_branch_scope_history')"), '2');
  assert.equal(sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='knowledge_code_refs' AND column_name='branch_id' AND is_nullable='NO'"), '1');
  assert.equal(sql("SELECT count(*) FROM pg_trigger WHERE tgname IN ('trg_knowledge_scope_revision','trg_knowledge_reference_branch')"), '2');
  sql("CREATE TABLE docker_smoke_marker (value text); INSERT INTO docker_smoke_marker VALUES ('persisted');");
  const oldId = docker(['inspect',name,'--format','{{.Id}}']);
  stopAndRemove();
  // Replacing a container is the deployment upgrade path, rather than only restarting it.
  docker(['run','--rm',...user,'--entrypoint','node','-v',`${directory}:/installation`,replacementImage,
    '/opt/analyzer-coder/deploy/configure.mjs','/installation',replacementImage,'--upgrade']);
  assert.equal(fs.readFileSync(path.join(directory,'.env'),'utf8').replace(/^ANALYZER_IMAGE=.*$/m,''), originalEnv.replace(/^ANALYZER_IMAGE=.*$/m,''), 'Upgrade must preserve all passwords and keys.');
  for (const [index,file] of ['application.yml','postgresql.conf','nginx.conf'].entries()) assert.equal(fs.readFileSync(path.join(directory,'config',file),'utf8'),configBefore[index]);
  start(replacementImage);
  await healthy();
  assert.notEqual(docker(['inspect',name,'--format','{{.Id}}']),oldId);
  assert.equal(sql('SELECT value FROM docker_smoke_marker'),'persisted');
  assert.equal(sql('SHOW max_connections'),'73');
  const me = await fetch(`${baseUrl()}/api/auth/me`, { headers: { Cookie: cookie } });
  assert.equal(me.status,200);
  assert.equal((await me.json()).id,account.id);
  assert.equal(await (await fetch(`${baseUrl()}/external-config-test`)).text(),'external-config-loaded');
  assert.ok(fs.existsSync(path.join(directory,'data/managed/smoke/.codegraph/codegraph.db')));
  stopAndRemove();
  succeeded = true;
  console.log('PASS: host data/config binds, Spring/Nginx/PostgreSQL external config, login, pgvector, Git, native CodeGraph indexing, MCP, graceful shutdown and container replacement preserving database/files/session/keys.');
} catch (error) {
  if (created) { try { console.error(docker(['logs','--tail','100',name])); } catch { /* Keep original error. */ } }
  console.error(error.message);
  console.error('Diagnostic directory: ' + directory);
  process.exitCode = 1;
} finally {
  if (created) {
    try { docker(['stop','--time','60',name]); docker(['rm',name]); }
    catch (error) { console.error(error.message); process.exitCode = 1; }
  }
  if (succeeded) {
    if (path.dirname(directory) !== path.join(root,'.runtime') || !path.basename(directory).startsWith('docker-smoke-')) throw new Error('Invalid cleanup path.');
    // PGDATA is owned by PostgreSQL (0700); the host test account cannot traverse it.
    if (process.platform !== 'win32') docker(['run','--rm','--entrypoint','sh','-v',`${directory}:/cleanup`,image,'-c','rm -rf /cleanup/data']);
    fs.rmSync(directory,{recursive:true,force:true});
  }
}
