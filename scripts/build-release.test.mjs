import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'analyzer-release-test-'));
  t.after(() => {
    assert.equal(path.dirname(root), os.tmpdir());
    assert.ok(path.basename(root).startsWith('analyzer-release-test-'));
    fs.rmSync(root, { recursive: true, force: true });
  });
  for (const dir of ['scripts', 'frontend/dist/assets', 'backend/target', 'docs', 'mocks']) fs.mkdirSync(path.join(root, dir), { recursive: true });
  fs.copyFileSync(path.join(repository, 'scripts/build-release.mjs'), path.join(root, 'scripts/build-release.mjs'));
  fs.cpSync(path.join(repository, 'deploy'), path.join(root, 'deploy'), { recursive: true });
  fs.writeFileSync(path.join(root, 'backend/target/codebase-knowledge-backend-1.0.0.jar'), 'fixture jar');
  fs.writeFileSync(path.join(root, 'frontend/dist/index.html'), '<html>fixture</html>');
  fs.writeFileSync(path.join(root, 'frontend/dist/assets/app.js'), 'fixture');
  fs.writeFileSync(path.join(root, 'docs/15-deployment-runbook.md'), 'fixture runbook');
  // Runtime secrets/data must never be copied from the source or template tree.
  fs.writeFileSync(path.join(root, '.env.production'), 'SECRET=not-for-release');
  fs.writeFileSync(path.join(root, 'deploy/components/.env'), 'SECRET=not-for-release');
  fs.mkdirSync(path.join(root, 'deploy/backend/data'), { recursive: true });
  fs.writeFileSync(path.join(root, 'deploy/backend/data/private.txt'), 'private');
  const mockFile = path.join(root, 'mocks/docker.cjs');
  fs.writeFileSync(mockFile, [
    "const cp = require('node:child_process');",
    "const fs = require('node:fs');",
    "const original = cp.spawnSync;",
    "cp.spawnSync = function(command, args, options) {",
    "  if (command !== 'docker') return original(command, args, options);",
    "  fs.appendFileSync(process.env.MOCK_CALLS, JSON.stringify(args) + '\\n');",
    "  if (process.env.MOCK_FAIL_SAVE && args[0] === 'save') return { status: 1, stderr: 'simulated save failure' };",
    "  if (args[0] === 'save') fs.writeFileSync(args[args.indexOf('--output') + 1], 'mock image tar');",
    "  const stdout = args[0] === 'image' ? JSON.stringify({ Os: 'linux', Architecture: process.env.MOCK_ARCH || 'amd64', Id: 'sha256:test', RepoDigests: ['test@sha256:fixture'] }) : (args[0] === 'info' ? 'linux' : '');",
    "  return { status: 0, stdout, stderr: '' };",
    "};",
    "require('node:module').syncBuiltinESMExports();",
  ].join('\n'));
  const calls = path.join(root, 'mocks/calls.jsonl');
  const launch = (args, extraEnv = {}) => spawnSync(process.execPath, [
    '--require', mockFile, path.join(root, 'scripts/build-release.mjs'),
    '--version', 'test-1', '--skip-build', '--output', 'output with spaces', ...args,
  ], {
    cwd: os.tmpdir(), encoding: 'utf8', windowsHide: true,
    env: { ...process.env, MOCK_CALLS: calls, ...extraEnv },
  });
  return { root, launch, calls, output: path.join(root, 'output with spaces/analyzer-coder-test-1') };
}
test('application-only archive uses relocatable templates, excludes secrets/data and hashes all payload files', t => {
  const f = fixture(t);
  const result = f.launch(['--without-images']);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(fs.existsSync(f.calls), false, 'application-only packaging must not call Docker');
  assert.equal(fs.existsSync(path.join(f.output, 'components/.env')), false);
  assert.equal(fs.existsSync(path.join(f.output, 'backend/data')), false);
  assert.equal(fs.existsSync(path.join(f.output, 'components/images/components.tar')), false);
  assert.equal(fs.existsSync(path.join(f.output, '.incomplete')), false);
  assert.match(fs.readFileSync(path.join(f.output, 'components/compose.yaml'), 'utf8'), /source: \.\.\/frontend\/dist/);
  for (const line of fs.readFileSync(path.join(f.output, 'SHA256SUMS'), 'utf8').trim().split('\n')) {
    const [, hash, name] = line.match(/^([0-9a-f]{64})  (.+)$/);
    assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(f.output, name))).digest('hex'), hash);
  }
  const archiveList = spawnSync('tar', ['-tzf', f.output + '.tar.gz'], { encoding: 'utf8', windowsHide: true });
  assert.equal(archiveList.status, 0);
  assert.match(archiveList.stdout, /backend\/config\/application.yml/);
  assert.doesNotMatch(archiveList.stdout, /private.txt|\.env.production|\.incomplete/);
  assert.equal(f.launch(['--without-images']).status, 1, 'refuse to overwrite an existing release');
});
test('full package pins exported image names into the environment template and records image identities', t => {
  const f = fixture(t);
  const result = f.launch([]);
  assert.equal(result.status, 0, result.stderr);
  const env = fs.readFileSync(path.join(f.output, 'components/components.env.example'), 'utf8');
  assert.match(env, /POSTGRES_IMAGE=analyzer-coder\/postgres:test-1-amd64/);
  assert.match(env, /NGINX_IMAGE=analyzer-coder\/nginx:test-1-amd64/);
  assert.match(env, /POSTGRES_STORAGE_TYPE=volume/);
  assert.match(env, /POSTGRES_DATA_SOURCE=postgres-data/);
  const compose = fs.readFileSync(path.join(f.output, 'components/compose.yaml'), 'utf8');
  assert.match(compose, /type: \$\{POSTGRES_STORAGE_TYPE:-volume\}/);
  assert.match(compose, /source: \$\{POSTGRES_DATA_SOURCE:-postgres-data\}/);
  const backendConfig = fs.readFileSync(path.join(f.output, 'backend/config/application.yml'), 'utf8');
  assert.match(backendConfig, /port: 18080/);
  const calls = fs.readFileSync(f.calls, 'utf8').trim().split('\n').map(JSON.parse);
  assert.equal(calls.filter(args => args[0] === 'pull' && args[2] === 'linux/amd64').length, 2);
  const save = calls.find(args => args[0] === 'save');
  assert.deepEqual(save.slice(-2), ['analyzer-coder/postgres:test-1-amd64', 'analyzer-coder/nginx:test-1-amd64']);
  const manifest = JSON.parse(fs.readFileSync(path.join(f.output, 'MANIFEST.txt'), 'utf8'));
  assert.equal(manifest.imagesIncluded, true);
  assert.equal(manifest.images.length, 2);
  assert.equal(manifest.images[0].id, 'sha256:test');
});
test('Docker save failure cannot produce a deliverable archive', t => {
  const f = fixture(t);
  const result = f.launch([], { MOCK_FAIL_SAVE: '1' });
  assert.equal(result.status, 1);
  assert.equal(fs.existsSync(f.output + '.tar.gz'), false);
  assert.equal(fs.existsSync(path.join(f.output, '.incomplete')), true);
});
test('wrong image architecture fails before export', t => {
  const f = fixture(t);
  const result = f.launch([], { MOCK_ARCH: 'arm64' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /architecture mismatch/);
  assert.equal(fs.existsSync(path.join(f.output, 'components/images/components.tar')), false);
});
test('unsafe version and unknown flags fail before creating release files', t => {
  const f = fixture(t);
  assert.equal(f.launch(['--version', '../escape', '--without-images']).status, 1);
  assert.equal(f.launch(['--unknown']).status, 1);
  assert.equal(fs.existsSync(f.output), false);
});
