import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const options = { version: null, platform: 'linux/amd64', output: 'release', runtimeImage: null };
const deliveryFiles = ['analyzer.sh', 'analyzer.ps1', 'compose.yaml', 'image.env', 'README.md'];
const imageFiles = ['configure.mjs', 'runtime.env.example', 'application.yml', 'postgresql.conf', 'nginx.conf'];
function command(executable, args, cwd = root, capture = false) {
  const result = spawnSync(executable, args, {
    cwd, encoding: 'utf8', windowsHide: true,
    stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
  });
  if (result.error || result.status !== 0) {
    if (capture && result.stderr) process.stderr.write(result.stderr);
    throw new Error(`${executable} ${args[0] ?? ''} failed: ${result.error?.message ?? result.status}`);
  }
  return result.stdout?.trim();
}
function docker(args, capture = false) { return command('docker', args, root, capture); }
function build(executable, args, cwd) {
  if (process.platform === 'win32') command('cmd.exe', ['/d', '/s', '/c', [executable, ...args].join(' ')], cwd);
  else command(executable, args, cwd);
}
async function hash(file) {
  const digest = crypto.createHash('sha256');
  for await (const chunk of fs.createReadStream(file)) digest.update(chunk);
  return digest.digest('hex');
}
async function buildWithRuntime(image) {
  const base = JSON.parse(docker(['image', 'inspect', options.runtimeImage, '--format', '{{json .}}'], true));
  if (`${base.Os}/${base.Architecture}` !== options.platform) throw new Error('Runtime image architecture mismatch.');
  docker(['run', '--rm', '--entrypoint', 'sh', options.runtimeImage, '-c',
    'set -eu; java -version; postgres --version; nginx -v; git --version; curl --version >/dev/null; command -v tini; python3 --version; codegraph --version']);
  const packages = JSON.parse(docker(['run', '--rm', '--entrypoint', 'npm', options.runtimeImage, 'list', '--global', '--depth=0', '--json'], true));
  for (const name of ['@colbymchenry/codegraph', `@colbymchenry/codegraph-linux-${base.Architecture === 'amd64' ? 'x64' : 'arm64'}`]) {
    if (packages.dependencies?.[name]?.version !== '1.6.0') throw new Error('Runtime must include ' + name + '@1.6.0');
  }
  const mcp = docker(['run', '--rm', '--entrypoint', 'sha256sum', options.runtimeImage,
    '/opt/analyzer-coder/mcp-server/package.json', '/opt/analyzer-coder/mcp-server/package-lock.json'], true).split(/\r?\n/);
  for (const [index, name] of ['package.json', 'package-lock.json'].entries()) {
    if (mcp[index]?.split(/\s+/)[0] !== await hash(path.join(root, 'mcp-server', name))) throw new Error('Runtime MCP lock differs; rebuild with the original Dockerfile.');
  }
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'analyzer-image-build-'));
  try {
    const frontend = path.join(temporary, 'frontend-source');
    fs.cpSync(path.join(root, 'frontend'), frontend, { recursive: true, filter: source => {
      const relative = path.relative(path.join(root, 'frontend'), source);
      return !relative.split(path.sep).some(part => ['node_modules', 'dist', '.git', '.env', '.npmrc'].includes(part) || part.startsWith('.env.'));
    } });
    build('npm', ['ci'], frontend);
    build('npm', ['run', 'build'], frontend);
    build('mvn', ['-B', '--no-transfer-progress', '-pl', 'backend', '-am', 'clean', 'package', '-DskipTests'], root);
    const jars = fs.readdirSync(path.join(root, 'backend/target')).filter(name => /^codebase-knowledge-backend-.+\.jar$/.test(name) && !/-(tests|sources|javadoc)\.jar$/.test(name));
    if (jars.length !== 1) throw new Error('Expected exactly one application JAR.');
    fs.copyFileSync(path.join(root, 'backend/target', jars[0]), path.join(temporary, 'app.jar'));
    fs.cpSync(path.join(frontend, 'dist'), path.join(temporary, 'frontend'), { recursive: true });
    fs.cpSync(path.join(root, 'mcp-server/src'), path.join(temporary, 'mcp-src'), { recursive: true });
    const templates = path.join(temporary, 'templates');
    fs.mkdirSync(templates);
    for (const name of [...imageFiles, 'entrypoint.sh', 'healthcheck.sh']) fs.copyFileSync(path.join(root, 'deploy/all-in-one', name), path.join(templates, name));
    fs.writeFileSync(path.join(temporary, '.dockerignore'), 'frontend-source\n');
    fs.writeFileSync(path.join(temporary, 'Dockerfile'), [
      `FROM ${options.runtimeImage}`,
      'RUN rm -rf /usr/share/nginx/html/* /opt/analyzer-coder/mcp-server/src',
      'COPY app.jar /opt/analyzer-coder/app.jar',
      'COPY frontend/ /usr/share/nginx/html/',
      'COPY mcp-src/ /opt/analyzer-coder/mcp-server/src/',
      'COPY templates/ /opt/analyzer-coder/deploy/',
      'COPY templates/nginx.conf /etc/nginx/conf.d/analyzer-coder.conf',
      'COPY templates/entrypoint.sh /usr/local/bin/analyzer-entrypoint',
      'COPY templates/healthcheck.sh /usr/local/bin/analyzer-healthcheck',
      'RUN chmod +x /usr/local/bin/analyzer-entrypoint /usr/local/bin/analyzer-healthcheck && nginx -t',
    ].join('\n') + '\n');
    docker(['build', '--platform', options.platform, '--tag', image, '--file', path.join(temporary, 'Dockerfile'), temporary]);
    return { image: options.runtimeImage, imageId: base.Id };
  } finally {
    if (path.dirname(temporary) !== os.tmpdir() || !path.basename(temporary).startsWith('analyzer-image-build-')) throw new Error('Invalid temporary build path.');
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}
async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help')) {
    console.log('Usage: node scripts/build-docker-image.mjs [--version VERSION] [--platform linux/amd64|linux/arm64] [--output DIR] [--runtime-image analyzer-coder:VERSION]');
    console.log('Defaults: version from project VERSION; output in project release/. Explicit version must match VERSION (CI: VERSION-ci.ID).');
    console.log('Builds and verifies one complete image, then delivers launchers, image.tar, checksums and a .tar.gz.');
    console.log('--runtime-image reuses a local verified complete runtime; rebuilds applications using local Node/npm, Java 17 and Maven.');
    return;
  }
  for (let i = 0; i < args.length; i++) {
    if (!['--version', '--platform', '--output', '--runtime-image'].includes(args[i])) throw new Error(`Unknown option: ${args[i]}`);
    const key = args[i] === '--runtime-image' ? 'runtimeImage' : args[i].slice(2);
    const value = args[++i];
    if (!value || value.startsWith('--')) throw new Error(`Missing value for ${args[i - 1]}`);
    options[key] = value;
  }
  const releaseVersion = fs.readFileSync(path.join(root, 'VERSION'), 'utf8').trim();
  const stableVersion = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
  if (!stableVersion.test(releaseVersion)) throw new Error('VERSION must contain MAJOR.MINOR.PATCH.');
  options.version ??= releaseVersion;
  const ciPrefix = releaseVersion + '-ci.';
  const validCiVersion = options.version.startsWith(ciPrefix) && /^[a-z0-9]+$/.test(options.version.slice(ciPrefix.length));
  if (options.version !== releaseVersion && !validCiVersion) {
    throw new Error('Release version must match VERSION; only VERSION-ci.ID is allowed for CI.');
  }
  if (options.version.length > 80) throw new Error('Image version is too long.');
  if (!['linux/amd64', 'linux/arm64'].includes(options.platform)) throw new Error('Unsupported image platform.');
  if (options.runtimeImage && !/^analyzer-coder:[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$/.test(options.runtimeImage)) throw new Error('Invalid runtime image.');
  const packageName = `analyzer-coder-docker-${options.version}`;
  const output = path.resolve(root, options.output);
  const destination = path.join(output, packageName);
  const archive = path.join(output, `${packageName}.tar.gz`);
  if ([destination, archive, archive + '.sha256'].some(file => fs.existsSync(file))) throw new Error('This release already exists; reuse it or update VERSION for an intentional new release. Existing deliveries are never overwritten.');
  command('tar', ['--version'], root, true);
  if (docker(['info', '--format', '{{.OSType}}'], true) !== 'linux') throw new Error('Start Docker with Linux containers first.');
  const image = `analyzer-coder:${options.version}`;
  let runtime = null;
  if (options.runtimeImage) runtime = await buildWithRuntime(image);
  else docker(['build', '--platform', options.platform, '--tag', image, '--file', 'Dockerfile', '.']);
  const details = JSON.parse(docker(['image', 'inspect', image, '--format', '{{json .}}'], true));
  if (`${details.Os}/${details.Architecture}` !== options.platform) throw new Error('Built image architecture mismatch.');
  console.log('Verifying external data/configuration, graph tools and container replacement...');
  command(process.execPath, [path.join(root, 'scripts/smoke-docker-image.mjs'), image]);
  fs.mkdirSync(destination, { recursive: true });
  const marker = path.join(destination, '.incomplete');
  fs.writeFileSync(marker, 'Packaging has not completed.\n');
  docker(['save', '--output', path.join(destination, 'image.tar'), image]);
  for (const name of deliveryFiles) {
    const text = fs.readFileSync(path.join(root, 'deploy/all-in-one', name), 'utf8').replaceAll('analyzer-coder:1.0.0', image).replaceAll('{{RELEASE_VERSION}}', options.version);
    fs.writeFileSync(path.join(destination, name), text);
  }
  if (process.platform !== 'win32') fs.chmodSync(path.join(destination, 'analyzer.sh'), 0o755);
  let sourceCommit = null, sourceDirty = null;
  try {
    sourceCommit = command('git', ['rev-parse', 'HEAD'], root, true);
    sourceDirty = Boolean(command('git', ['status', '--porcelain'], root, true));
  } catch { /* Source archives may omit Git metadata. */ }
  fs.writeFileSync(path.join(destination, 'MANIFEST.json'), JSON.stringify({
    releaseVersion: options.version, image, platform: options.platform, imageId: details.Id, created: details.Created,
    codegraphVersion: '1.6.0', sourceCommit, sourceDirty, runtime,
    dataMount: './data:/data', configMount: './config:/config:ro',
  }, null, 2) + '\n');
  const sums = [];
  for (const name of fs.readdirSync(destination).filter(name => name !== '.incomplete').sort()) sums.push(`${await hash(path.join(destination, name))}  ${name}`);
  fs.writeFileSync(path.join(destination, 'SHA256SUMS'), sums.join('\n') + '\n');
  fs.unlinkSync(marker);
  try {
    command('tar', ['-czf', `${packageName}.tar.gz`, packageName], output);
    command('tar', ['-tzf', `${packageName}.tar.gz`], output, true);
    fs.writeFileSync(archive + '.sha256', `${await hash(archive)}  ${packageName}.tar.gz\n`);
  } catch (error) {
    fs.writeFileSync(marker, 'Archive creation or validation failed.\n');
    throw error;
  }
  console.log(`Offline image package: ${destination}\nOffline archive: ${archive}`);
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
