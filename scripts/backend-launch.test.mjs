import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import net from 'node:net';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const supported = ['linux', 'win32'].includes(process.platform);
test('backend launcher uses its own directory, checks health, rejects wrong identity and stops only its JAR', { skip: !supported, timeout: 240000 }, async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'analyzer-launch-test-'));
  const backend = path.join(root, 'directory with spaces', 'backend');
  fs.mkdirSync(path.join(backend, 'config'), { recursive: true });
  for (const file of ['backend.sh', 'backend.ps1']) fs.copyFileSync(path.join(repository, 'deploy/backend', file), path.join(backend, file));
  function command(exe, args) {
    const result = spawnSync(exe, args, { cwd: root, encoding: 'utf8', windowsHide: true, timeout: 90000 });
    assert.equal(result.status, 0, result.error?.message ?? result.stderr);
  }
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  const config = path.join(backend, 'config/application.yml');
  fs.writeFileSync(config, 'port: ' + port + '\n');
  fs.writeFileSync(path.join(root, 'LifecycleProbe.java'), [
    'import com.sun.net.httpserver.HttpServer;',
    'import java.net.InetSocketAddress;',
    'import java.nio.file.*;',
    'public class LifecycleProbe {',
    '  public static void main(String[] args) throws Exception {',
    '    String config = Files.readString(Path.of("config/application.yml"));',
    '    if (config.contains("exit-immediately")) System.exit(3);',
    '    int port = Integer.parseInt(config.split(":")[1].trim());',
    '    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);',
    '    server.createContext("/actuator/health", exchange -> {',
    '      byte[] body = "{\\"status\\":\\"UP\\"}".getBytes();',
    '      exchange.getResponseHeaders().add("Content-Type", "application/json");',
    '      exchange.sendResponseHeaders(200, body.length);',
    '      exchange.getResponseBody().write(body); exchange.close();',
    '    });',
    '    server.start();',
    '  }',
    '}',
  ].join('\n'));
  command('javac', ['-d', path.join(root, 'classes'), path.join(root, 'LifecycleProbe.java')]);
  command('jar', ['--create', '--file', path.join(backend, 'app.jar'), '--main-class', 'LifecycleProbe', '-C', path.join(root, 'classes'), '.']);
  function launch(action) {
    const exe = process.platform === 'win32' ? 'pwsh' : 'bash';
    const args = process.platform === 'win32'
      ? ['-NoProfile', '-File', path.join(backend, 'backend.ps1'), action, '-Port', String(port), '-TimeoutSeconds', '15']
      : [path.join(backend, 'backend.sh'), action, '--port', String(port), '--timeout', '15'];
    return spawnSync(exe, args, { cwd: os.tmpdir(), encoding: 'utf8', windowsHide: true, timeout: 60000 });
  }
  const pidFile = path.join(backend, 'run/backend.pid');
  let goodIdentity;
  t.after(() => {
    if (goodIdentity) fs.writeFileSync(pidFile, goodIdentity);
    const stopped = launch('stop');
    if (stopped.status !== 0) throw new Error('Probe cleanup failed: ' + stopped.stderr);
    assert.equal(path.dirname(root), os.tmpdir());
    assert.ok(path.basename(root).startsWith('analyzer-launch-test-'));
    fs.rmSync(root, { recursive: true, force: true });
  });
  fs.writeFileSync(config, "password: 'replace-with-secret'\n");
  assert.notEqual(launch('start').status, 0, 'unconfigured template must not start Java');
  fs.writeFileSync(config, 'port: ' + port + '\n');
  const started = launch('start');
  assert.equal(started.status, 0, started.error?.message ?? started.stderr);
  goodIdentity = fs.readFileSync(pidFile, 'utf8');
  assert.equal(launch('status').status, 0, 'healthy managed process is reported');
  assert.equal(launch('start').status, 0, 'second start is idempotent');
  assert.equal(fs.readFileSync(pidFile, 'utf8'), goodIdentity, 'second start must not replace the process');

  if (process.platform === 'win32') {
    const invalid = JSON.parse(goodIdentity);
    invalid.created = 'wrong-creation-time';
    fs.writeFileSync(pidFile, JSON.stringify(invalid));
  } else {
    const invalid = goodIdentity.split('\n');
    invalid[1] = 'wrong-creation-time';
    fs.writeFileSync(pidFile, invalid.join('\n'));
  }
  assert.notEqual(launch('stop').status, 0, 'identity mismatch must not kill a process');
  fs.writeFileSync(pidFile, goodIdentity);
  assert.equal(launch('status').status, 0, 'process survived the refused stop');
  assert.equal(launch('stop').status, 0);
  goodIdentity = null;
  assert.equal(fs.existsSync(pidFile), false);
  assert.notEqual(launch('status').status, 0, 'stopped status is nonzero');
  fs.writeFileSync(config, 'exit-immediately: 1\n');
  assert.notEqual(launch('start').status, 0, 'Java early exit must fail the startup');
  assert.equal(fs.existsSync(pidFile), false, 'failed startup must remove its PID');
});
