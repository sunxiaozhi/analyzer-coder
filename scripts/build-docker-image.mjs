import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const options = { version: '1.0.0', platform: 'linux/amd64', output: 'release' };
function run(args, capture = false) {
  const result = spawnSync('docker', args, {
    cwd: root, encoding: 'utf8', windowsHide: true,
    stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
  });
  if (result.error || result.status !== 0) {
    if (capture && result.stderr) process.stderr.write(result.stderr);
    throw new Error(`docker ${args[0]} failed: ${result.error?.message ?? result.status}`);
  }
  return result.stdout?.trim();
}
async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help')) {
    console.log('Usage: node scripts/build-docker-image.mjs [--version 1.0.0] [--platform linux/amd64|linux/arm64] [--output release]');
    console.log('Builds the complete image inside Docker, then exports an offline directory with image.tar, Compose, configuration and SHA256SUMS.');
    return;
  }
  for (let i = 0; i < args.length; i++) {
    if (!['--version', '--platform', '--output'].includes(args[i])) throw new Error(`Unknown option: ${args[i]}`);
    const key = args[i].slice(2);
    const value = args[++i];
    if (!value || value.startsWith('--')) throw new Error(`Missing value for --${key}`);
    options[key] = value;
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$/.test(options.version)) throw new Error('Invalid image version.');
  if (!['linux/amd64', 'linux/arm64'].includes(options.platform)) throw new Error('Unsupported image platform.');
  const destination = path.resolve(root, options.output, `analyzer-coder-docker-${options.version}`);
  if (fs.existsSync(destination)) throw new Error('Output exists; choose another version or output.');
  if (run(['info', '--format', '{{.OSType}}'], true) !== 'linux') throw new Error('Start Docker with Linux containers first.');
  const image = `analyzer-coder:${options.version}`;
  run(['build', '--platform', options.platform, '--tag', image, '--file', 'Dockerfile', '.']);
  const details = JSON.parse(run(['image', 'inspect', image, '--format', '{{json .}}'], true));
  if (`${details.Os}/${details.Architecture}` !== options.platform) throw new Error('Built image architecture mismatch.');
  console.log('Verifying container startup, graph tools and persistent data before export...');
  const smoke = spawnSync(process.execPath, [path.join(root, 'scripts/smoke-docker-image.mjs'), image], {
    cwd: root, stdio: 'inherit', windowsHide: true,
  });
  if (smoke.error || smoke.status !== 0) throw new Error(`Image verification failed: ${smoke.error?.message ?? smoke.status}`);
  fs.mkdirSync(destination, { recursive: true });
  run(['save', '--output', path.join(destination, 'image.tar'), image]);
  for (const file of ['compose.yaml', 'runtime.env.example', 'README.md']) {
    let contents = fs.readFileSync(path.join(root, 'deploy/all-in-one', file), 'utf8');
    contents = contents.replaceAll('analyzer-coder:1.0.0', image);
    fs.writeFileSync(path.join(destination, file), contents);
  }
  fs.writeFileSync(path.join(destination, 'MANIFEST.json'), JSON.stringify({
    image, platform: options.platform, imageId: details.Id, created: details.Created,
    codegraphVersion: '1.6.0',
  }, null, 2) + '\n');
  const sums = [];
  for (const name of fs.readdirSync(destination).sort()) {
    const hash = crypto.createHash('sha256');
    for await (const chunk of fs.createReadStream(path.join(destination, name))) hash.update(chunk);
    sums.push(`${hash.digest('hex')}  ${name}`);
  }
  fs.writeFileSync(path.join(destination, 'SHA256SUMS'), sums.join('\n') + '\n');
  console.log(`Offline image package: ${destination}`);
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
