import fs from 'node:fs';
import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const templates = path.dirname(fileURLToPath(import.meta.url));
export function initialize(directory, image, upgrade = false) {
  if (!/^analyzer-coder:[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$/.test(image)) throw new Error('Invalid image tag.');
  fs.mkdirSync(directory, { recursive: true });
  const envFile = path.join(directory, '.env');
  const secret = () => randomBytes(32).toString('hex');
  const writeNew = (file, contents, mode) => {
    try { fs.writeFileSync(file, contents, { flag: 'wx', mode }); }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
  };
  const env = fs.readFileSync(path.join(templates, 'runtime.env.example'), 'utf8')
    .replace('analyzer-coder:1.0.0', image)
    .replace('replace-with-database-password', secret())
    .replace('replace-with-strong-admin-password', `Admin-${randomBytes(18).toString('hex')}-9!`)
    .replace('replace-with-independent-random-secret-at-least-32-characters', secret())
    .replace('replace-with-another-random-secret-at-least-32-characters', secret());
  writeNew(envFile, env, 0o600);
  for (const name of ['data', 'config', 'backups']) fs.mkdirSync(path.join(directory, name), { recursive: true });
  for (const name of ['application.yml', 'nginx.conf', 'postgresql.conf']) {
    writeNew(path.join(directory, 'config', name), fs.readFileSync(path.join(templates, name)), 0o644);
  }
  if (upgrade) {
    const previous = fs.readFileSync(envFile, 'utf8');
    const line = `ANALYZER_IMAGE=${image}`;
    const next = /^ANALYZER_IMAGE=.*$/m.test(previous)
      ? previous.replace(/^ANALYZER_IMAGE=.*$/m, line)
      : `${previous.trimEnd()}\n${line}\n`;
    const temporary = `${envFile}.${randomBytes(8).toString('hex')}.tmp`;
    fs.writeFileSync(temporary, next, { flag: 'wx', mode: 0o600 });
    try { fs.renameSync(temporary, envFile); }
    catch (error) { fs.unlinkSync(temporary); throw error; }
  }
  return envFile;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length < 2 || args.length > 3 || (args[2] && args[2] !== '--upgrade')) {
    throw new Error('Usage: configure.mjs DIRECTORY analyzer-coder:VERSION [--upgrade]');
  }
  initialize(path.resolve(args[0]), args[1], args[2] === '--upgrade');
  console.log('Configuration ready. Existing passwords, keys and config files were preserved.');
}
