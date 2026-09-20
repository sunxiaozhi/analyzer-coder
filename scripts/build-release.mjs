import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const options = {
  version: new Date().toISOString().replace(/[-:]/g, '').replace(/\..+/, '').replace('T', '-'),
  output: 'release',
  platform: 'linux/amd64',
  withoutImages: false,
  skipBuild: false,
};
function usage() {
  console.log('Usage: build-release.sh --version VERSION [--output DIR] [--platform linux/amd64|linux/arm64] [--without-images] [--skip-build]');
  console.log('Builds frontend/JAR, exports component images, and creates a directory plus .tar.gz.');
  console.log('--without-images: application-only delivery; Docker is not required.');
  console.log('--skip-build: package existing frontend/dist and exactly one backend/target application JAR.');
}
function run(command, args, cwd = root, capture = false) {
  const result = spawnSync(command, args, {
    cwd, stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
    encoding: 'utf8', windowsHide: true,
  });
  if (result.error || result.status !== 0) {
    if (capture && result.stderr) process.stderr.write(result.stderr);
    throw new Error(command + ' failed: ' + (result.error?.message ?? result.status));
  }
  return result.stdout?.trim();
}
function build(command, args, cwd) {
  // Only fixed build commands reach cmd.exe. No user-provided shell fragments.
  if (process.platform === 'win32') run('cmd.exe', ['/d', '/s', '/c', [command, ...args].join(' ')], cwd);
  else run(command, args, cwd);
}
function copy(source, destination) {
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.cpSync(source, destination, { recursive: true, errorOnExist: true, force: false });
}
function filesUnder(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name)).flatMap(entry => {
    const full = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) throw new Error('Release must not contain symlinks: ' + full);
    return entry.isDirectory() ? filesUnder(full) : [full];
  });
}
async function sha256(file) {
  const hash = crypto.createHash('sha256');
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}
async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help') || args.includes('-h')) { usage(); return; }
  for (let i = 0; i < args.length; i++) {
    switch (args[i]) {
      case '--without-images': options.withoutImages = true; break;
      case '--skip-build': options.skipBuild = true; break;
      case '--version': case '--output': case '--platform': {
        const key = args[i].slice(2);
        const value = args[++i];
        if (!value || value.startsWith('--')) throw new Error('Missing value for --' + key);
        options[key] = value;
        break;
      }
      default: throw new Error('Unknown option: ' + args[i]);
    }
  }
  if (Number(process.versions.node.split('.')[0]) < 20) throw new Error('Node.js 20+ required on the build machine.');
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(options.version)) throw new Error('Invalid version (1-80 letters, digits, dot, underscore or hyphen).');
  if (!['linux/amd64', 'linux/arm64'].includes(options.platform)) throw new Error('Unsupported platform.');
  const outputRoot = path.resolve(root, options.output);
  const packageName = 'analyzer-coder-' + options.version;
  const packageRoot = path.join(outputRoot, packageName);
  const archive = path.join(outputRoot, packageName + '.tar.gz');
  if (fs.existsSync(packageRoot) || fs.existsSync(archive)) throw new Error('Output exists; choose another version/output. No files were overwritten.');
  run('tar', ['--version'], root, true);
  if (!options.withoutImages) {
    if (run('docker', ['info', '--format', '{{.OSType}}'], root, true) !== 'linux') throw new Error('Docker must use Linux containers.');
    run('docker', ['compose', 'version'], root, true);
  }
  if (!options.skipBuild) {
    console.log('[1/4] Building frontend and backend...');
    build('npm', ['ci'], path.join(root, 'frontend'));
    build('npm', ['run', 'build'], path.join(root, 'frontend'));
    build('mvn', ['-pl', 'backend', '-am', 'clean', 'package', '-DskipTests'], root);
  }
  const target = path.join(root, 'backend/target');
  const jars = fs.readdirSync(target).filter(name => /^codebase-knowledge-backend-.+\.jar$/.test(name) && !/-(sources|javadoc|tests)\.jar$/.test(name));
  if (jars.length !== 1) throw new Error('Expected exactly one application JAR in backend/target; found ' + jars.length);
  if (!fs.existsSync(path.join(root, 'frontend/dist/index.html'))) throw new Error('Missing frontend/dist/index.html.');
  console.log('[2/4] Assembling release directory...');
  fs.mkdirSync(packageRoot, { recursive: true });
  fs.writeFileSync(path.join(packageRoot, '.incomplete'), 'Packaging has not completed.\n');
  copy(path.join(target, jars[0]), path.join(packageRoot, 'backend/app.jar'));
  copy(path.join(root, 'frontend/dist'), path.join(packageRoot, 'frontend/dist'));
  for (const file of [
    'backend/config/application.yml', 'backend/backend.sh', 'backend/backend.ps1',
    'components/compose.yaml', 'components/components.env.example', 'components/nginx/nginx.conf',
  ]) copy(path.join(root, 'deploy', file), path.join(packageRoot, file));
  copy(path.join(root, 'docs/15-deployment-runbook.md'), path.join(packageRoot, 'README.md'));
  if (process.platform !== 'win32') fs.chmodSync(path.join(packageRoot, 'backend/backend.sh'), 0o755);

  const envPath = path.join(packageRoot, 'components/components.env.example');
  let componentEnv = fs.readFileSync(envPath, 'utf8');
  const imageRecords = [];
  if (!options.withoutImages) {
    console.log('[3/4] Exporting PG/pgvector and Nginx images...');
    for (const [key, name] of [['POSTGRES_IMAGE', 'postgres'], ['NGINX_IMAGE', 'nginx']]) {
      const source = componentEnv.match(new RegExp('^' + key + '=(.+)$', 'm'))?.[1].trim();
      if (!source) throw new Error('Missing image in component template: ' + key);
      run('docker', ['pull', '--platform', options.platform, source]);
      const details = JSON.parse(run('docker', ['image', 'inspect', source, '--format', '{{json .}}'], root, true));
      if (details.Os + '/' + details.Architecture !== options.platform) throw new Error('Image architecture mismatch: ' + source);
      const exported = 'analyzer-coder/' + name + ':' + options.version + '-' + details.Architecture;
      run('docker', ['tag', source, exported]);
      componentEnv = componentEnv.replace(new RegExp('^' + key + '=.+$', 'm'), key + '=' + exported);
      imageRecords.push({ source, exported, id: details.Id, digests: details.RepoDigests });
    }
    const imageDir = path.join(packageRoot, 'components/images');
    fs.mkdirSync(imageDir, { recursive: true });
    run('docker', ['save', '--output', path.join(imageDir, 'components.tar'), ...imageRecords.map(record => record.exported)]);
    fs.writeFileSync(envPath, componentEnv, 'utf8');
  } else console.log('[3/4] Component images omitted (--without-images).');
  const manifest = {
    version: options.version, created: new Date().toISOString(), platform: options.platform,
    imagesIncluded: !options.withoutImages, sourceCommit: null, sourceDirty: null, images: imageRecords,
  };
  try {
    manifest.sourceCommit = run('git', ['rev-parse', 'HEAD'], root, true);
    manifest.sourceDirty = Boolean(run('git', ['status', '--porcelain'], root, true));
  } catch { /* Source archives may not have Git metadata. */ }
  fs.writeFileSync(path.join(packageRoot, 'MANIFEST.txt'), JSON.stringify(manifest, null, 2) + '\n');

  console.log('[4/4] Hashing and compressing release...');
  const checksums = [];
  for (const file of filesUnder(packageRoot)) {
    if (path.basename(file) === '.incomplete') continue;
    checksums.push(await sha256(file) + '  ' + path.relative(packageRoot, file).split(path.sep).join('/'));
  }
  fs.writeFileSync(path.join(packageRoot, 'SHA256SUMS'), checksums.join('\n') + '\n');
  // The marker is only removed for compression; restored if compression/validation fails.
  fs.unlinkSync(path.join(packageRoot, '.incomplete'));
  try {
    run('tar', ['-czf', packageName + '.tar.gz', packageName], outputRoot);
    run('tar', ['-tzf', packageName + '.tar.gz'], outputRoot, true);
  } catch (error) {
    fs.writeFileSync(path.join(packageRoot, '.incomplete'), 'Archive creation/validation failed.\n');
    throw error;
  }
  console.log('Release directory: ' + packageRoot);
  console.log('Release archive: ' + archive);
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
