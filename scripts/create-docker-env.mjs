import fs from 'node:fs';
import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
if (args.includes('--help')) {
  console.log('Usage: node scripts/create-docker-env.mjs [path/to/.env]');
  console.log('Generates independent random secrets; never overwrites an existing file. Read the initial admin password from the generated file.');
} else {
  if (args.length > 1 || args[0]?.startsWith('--')) throw new Error('Expected one output file path.');
  const destination = path.resolve(root, args[0] || 'deploy/all-in-one/.env');
  const secret = () => randomBytes(32).toString('hex');
  const template = fs.readFileSync(path.join(root, 'deploy/all-in-one/runtime.env.example'), 'utf8');
  const content = template
    .replace('replace-with-database-password', secret())
    .replace('replace-with-strong-admin-password', `Admin-${randomBytes(18).toString('hex')}-9!`)
    .replace('replace-with-independent-random-secret-at-least-32-characters', secret())
    .replace('replace-with-another-random-secret-at-least-32-characters', secret());
  fs.writeFileSync(destination, content, { flag: 'wx', mode: 0o600 });
  console.log(`Configuration created: ${destination}\nRead APP_INITIAL_ADMIN_PASSWORD from this file; preserve both master keys.`);
}
