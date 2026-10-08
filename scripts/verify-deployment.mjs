import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

if(process.argv.includes('--help')) {
  console.log('Usage: node scripts/verify-deployment.mjs EXTRACTED_RELEASE_DIRECTORY\nTests actual install/repeated start, backup, edited configuration and one-image upgrade with isolated host directories and a unique Compose project.');
  process.exit(0);
}
const repository=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const packet=path.resolve(process.argv[2] ?? '');
if(!process.argv[2] || !fs.existsSync(path.join(packet,'SHA256SUMS')))throw new Error('Provide a complete extracted release.');
const id=crypto.randomBytes(8).toString('hex');
const root=path.join(repository,'.runtime','deployment-acceptance-'+id);
const installed=path.join(root,'installed app');
const next=path.join(root,'new release');
const project='analyzer-deploy-test-'+id;
const image=fs.readFileSync(path.join(packet,'image.env'),'utf8').trim().slice('ANALYZER_IMAGE='.length);
const replacement='analyzer-coder:acceptance-'+id;
const files=['analyzer.sh','analyzer.ps1','compose.yaml','image.env','README.md','MANIFEST.json','SHA256SUMS'];
const environment={...process.env,COMPOSE_PROJECT_NAME:project,ANALYZER_INSTALLATION_ROOT:installed};
let succeeded=false, tagged=false;
function run(executable,args,capture=true,env=environment){
  const result=spawnSync(executable,args,{env,encoding:'utf8',windowsHide:true,timeout:360000,stdio:capture?'pipe':'inherit'});
  if(result.error || result.status!==0)throw new Error(`${executable} ${args[0]} failed: ${result.error?.message ?? result.stderr ?? result.status}`);
  return result.stdout?.trim();
}
function docker(args){return run('docker',args);}
function launcher(action,packageDirectory){
  const args=process.platform==='win32'?['-NoProfile','-File',path.join(installed,'analyzer.ps1'),action,...(packageDirectory?['-PackageDirectory',packageDirectory]:[])]:[path.join(installed,'analyzer.sh'),action,...(packageDirectory?[packageDirectory]:[])];
  return run(process.platform==='win32'?'pwsh':'bash',args);
}
function compose(args){return docker(['compose','--project-directory',installed,'--env-file',path.join(installed,'.env'),'-f',path.join(installed,'compose.yaml'),...args]);}
function baseUrl(){
  const cid=compose(['ps','--quiet','analyzer']);
  const ports=JSON.parse(docker(['inspect',cid,'--format','{{json .NetworkSettings.Ports}}']));
  return {cid,url:`http://127.0.0.1:${ports['8080/tcp'][0].HostPort}`};
}
async function hash(file){
  const result=crypto.createHash('sha256');
  for await(const chunk of fs.createReadStream(file))result.update(chunk);
  return result.digest('hex');
}
try{
  fs.mkdirSync(installed,{recursive:true});fs.mkdirSync(next,{recursive:true});
  for(const name of files){fs.copyFileSync(path.join(packet,name),path.join(installed,name));fs.copyFileSync(path.join(packet,name),path.join(next,name));}
  fs.linkSync(path.join(packet,'image.tar'),path.join(installed,'image.tar'));
  launcher('init');
  const envFile=path.join(installed,'.env');
  fs.writeFileSync(envFile,fs.readFileSync(envFile,'utf8').replace('APP_HTTP_BIND_ADDRESS=0.0.0.0','APP_HTTP_BIND_ADDRESS=127.0.0.1').replace('APP_HTTP_PORT=18081','APP_HTTP_PORT=0'));
  launcher('start');
  const {cid:oldId,url}=baseUrl();
  launcher('start');
  assert.equal(baseUrl().cid,oldId,'repeated start should keep a healthy unchanged container');
  const before=fs.readFileSync(envFile,'utf8');
  const password=before.match(/^APP_INITIAL_ADMIN_PASSWORD=(.*)$/m)[1].trim();
  const login=await fetch(url+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password})});
  assert.equal(login.status,200);
  const cookie=login.headers.get('set-cookie').split(';')[0];
  docker(['exec','-u','postgres',oldId,'psql','-U','codebase_kb','-d','codebase_kb','-c',"CREATE TABLE deployment_upgrade_marker (value text); INSERT INTO deployment_upgrade_marker VALUES ('preserved');"]);
  docker(['exec','-u','analyzer',oldId,'node','-e',"require('node:fs').writeFileSync('/data/managed/upgrade-marker.txt','host data persists')"]);
  fs.appendFileSync(path.join(installed,'config/application.yml'),'\n# Preserve this installation-specific configuration.\n');
  const config=fs.readFileSync(path.join(installed,'config/application.yml'),'utf8');
  launcher('backup');
  const backups=fs.readdirSync(path.join(installed,'backups')).filter(name=>name.endsWith('.tar.gz'));
  assert.equal(backups.length,1);
  const entries=run('tar',['-tzf',path.join(installed,'backups',backups[0])]);
  assert.match(entries,/data\/managed\/upgrade-marker\.txt/);assert.match(entries,/config\/application\.yml/);assert.match(entries,/\.env/);
  assert.equal((await fetch(baseUrl().url+'/api/auth/me',{headers:{Cookie:cookie}})).status,200,'backup must resume the application');
  docker(['tag',image,replacement]);tagged=true;
  docker(['save','--output',path.join(next,'image.tar'),replacement]);
  fs.writeFileSync(path.join(next,'image.env'),`ANALYZER_IMAGE=${replacement}\n`);
  const sums=[];
  for(const name of fs.readdirSync(next).filter(name=>name!=='SHA256SUMS').sort())sums.push(`${await hash(path.join(next,name))}  ${name}`);
  fs.writeFileSync(path.join(next,'SHA256SUMS'),sums.join('\n')+'\n');
  launcher('upgrade',next);
  const {cid:newId,url:newUrl}=baseUrl();
  assert.notEqual(newId,oldId,'upgrade must replace the container');
  assert.equal(docker(['inspect',newId,'--format','{{.Config.Image}}']),replacement);
  assert.equal(fs.readFileSync(envFile,'utf8'),before.replace(/^ANALYZER_IMAGE=.*$/m,`ANALYZER_IMAGE=${replacement}`));
  assert.equal(fs.readFileSync(path.join(installed,'config/application.yml'),'utf8'),config);
  assert.equal(fs.readFileSync(path.join(installed,'data/managed/upgrade-marker.txt'),'utf8'),'host data persists');
  assert.equal(docker(['exec','-u','postgres',newId,'psql','-U','codebase_kb','-d','codebase_kb','-Atc','SELECT value FROM deployment_upgrade_marker']),'preserved');
  assert.equal((await fetch(newUrl+'/api/auth/me',{headers:{Cookie:cookie}})).status,200);
  launcher('stop');
  succeeded=true;
  console.log('PASS: actual launcher initialization, repeated start, host data/configuration, consistent backup/resume and one-image upgrade preserving database/files/session/keys.');
} catch(error){
  try{console.error(compose(['logs','--tail','100','analyzer']));}catch{/* Keep original error. */}
  console.error(error.message+'\nDiagnostic directory: '+root);process.exitCode=1;
} finally {
  if(fs.existsSync(path.join(installed,'.env'))){try{compose(['down']);}catch(error){console.error(error.message);process.exitCode=1;}}
  if(tagged){try{docker(['image','rm',replacement]);}catch(error){console.error(error.message);process.exitCode=1;}}
  if(succeeded){
    if(path.dirname(root)!==path.join(repository,'.runtime')||!path.basename(root).startsWith('deployment-acceptance-'))throw new Error('Invalid acceptance cleanup path.');
    // Only the isolated test mount is removed; PostgreSQL owns its files (0700).
    if(process.platform!=='win32')docker(['run','--rm','--entrypoint','sh','-v',`${installed}:/cleanup`,image,'-c','rm -rf /cleanup/data']);
    fs.rmSync(root,{recursive:true,force:true});
  }
}
