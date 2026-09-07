import { spawnSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { randomBytes, randomUUID } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

if (process.argv.includes('--help')) {
  console.log('Usage: node scripts/verify-core.mjs [--port=15439]\nRuns frontend, backend (including PostgreSQL/pgvector integration), MCP and script tests.\nRequires installed frontend/MCP dependencies, Java, Maven and a running Docker engine.\nCreates a separate temporary database; never uses configured business database credentials.');
  process.exit(0);
}
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const portArg = process.argv.find(value => value.startsWith('--port='));
const port = Number(portArg?.slice(7) ?? 15439);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid test database port');
const runId = randomUUID();
const container = 'analyzer-core-test-' + runId;
const dataRoot = path.join(root, '.runtime', 'verification', runId);
const password = randomBytes(24).toString('hex');
const environment = {
  ...process.env,
  POSTGRES_PASSWORD: password,
  APP_RUN_POSTGRES_IT: 'true',
  APP_DATASOURCE_URL: 'jdbc:postgresql://127.0.0.1:' + port + '/analyzer_core_test',
  APP_DATASOURCE_USERNAME: 'analyzer_core_test',
  APP_DATASOURCE_PASSWORD: password,
  APP_INITIAL_ADMIN_USERNAME: 'verification_admin',
  APP_INITIAL_ADMIN_PASSWORD: 'Verify-' + randomBytes(24).toString('hex') + '!',
  APP_REPOSITORY_ALLOWED_ROOTS: dataRoot,
  APP_MANAGED_DATA_ROOT: dataRoot,
  APP_LLM_MASTER_KEY: randomBytes(32).toString('hex'),
};
function run(command, args, cwd = root, quiet = false) {
  const result = spawnSync(command, args, {
    cwd, env: environment, stdio: quiet ? 'pipe' : 'inherit',
    encoding: 'utf8', windowsHide: true, timeout: 600_000,
  });
  if (result.error || result.status !== 0) {
    if (quiet && result.stderr) console.error(result.stderr);
    throw new Error(command + ' failed (exit ' + result.status + ')');
  }
  return result.stdout;
}
let created = false;
try {
  run('docker', ['info', '--format', '{{.ServerVersion}}'], root, true);
  mkdirSync(dataRoot, { recursive: true });
  // Mark before run so a container created but unable to bind its port is also cleaned up.
  created = true;
  run('docker', ['run', '-d', '--name', container, '--label', 'analyzer.verification=' + runId,
    '-p', '127.0.0.1:' + port + ':5432', '-e', 'POSTGRES_DB=analyzer_core_test',
    '-e', 'POSTGRES_USER=analyzer_core_test', '-e', 'POSTGRES_PASSWORD',
    'pgvector/pgvector:pg17'], root, true);
  let ready = false;
  for (let attempt = 0; attempt < 30; attempt++) {
    const result = spawnSync('docker', ['exec', container, 'pg_isready', '-U', 'analyzer_core_test', '-d', 'analyzer_core_test'],
      { stdio: 'ignore', windowsHide: true, timeout: 5000 });
    if (result.status === 0) { ready = true; break; }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  if (!ready) throw new Error('Temporary database did not become ready');
  const frontend = path.join(root, 'frontend');
  run(process.execPath, ['node_modules/vitest/vitest.mjs', 'run'], frontend);
  run(process.execPath, ['node_modules/vue-tsc/bin/vue-tsc.js', '--noEmit'], frontend);
  run(process.execPath, ['node_modules/vite/bin/vite.js', 'build'], frontend);
  if (process.platform === 'win32') run('cmd.exe', ['/d', '/s', '/c', 'mvn -pl backend -am -Dtest=*Test,*Tests,*IT test']);
  else run('mvn', ['-pl', 'backend', '-am', '-Dtest=*Test,*Tests,*IT', 'test']);
  run(process.execPath, ['--test'], path.join(root, 'mcp-server'));
  run(process.execPath, ['--test', 'scripts/evaluate-quality.test.mjs', 'scripts/ci-task-review.test.mjs']);
  console.log('Core verification passed, including the isolated PostgreSQL import-to-answer flow.');
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
} finally {
  if (created) {
    const result = spawnSync('docker', ['rm', '-f', container], { stdio: 'pipe', windowsHide: true, timeout: 30_000 });
    if (result.status !== 0) {
      console.error('Could not remove verification container: ' + container);
      process.exitCode = 1;
    }
  }
}

