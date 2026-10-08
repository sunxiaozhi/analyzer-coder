import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { initialize } from '../deploy/all-in-one/configure.mjs';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const template = path.join(repository, 'deploy/all-in-one');
function temporary(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'analyzer-deployment-test-'));
  t.after(() => {
    assert.equal(path.dirname(directory), os.tmpdir());
    assert.ok(path.basename(directory).startsWith('analyzer-deployment-test-'));
    fs.rmSync(directory, { recursive: true, force: true });
  });
  return directory;
}
test('first install creates independent secrets; initialization and upgrade preserve edited configuration and data', t => {
  const root = temporary(t);
  initialize(root, 'analyzer-coder:install');
  const env = fs.readFileSync(path.join(root, '.env'), 'utf8');
  assert.doesNotMatch(env, /replace-with-/);
  const values = Object.fromEntries(env.split(/\r?\n/).filter(line => line && !line.startsWith('#')).map(line => {
    const index = line.indexOf('='); return [line.slice(0,index),line.slice(index+1)];
  }));
  assert.equal(new Set([values.POSTGRES_PASSWORD,values.APP_LLM_MASTER_KEY,values.APP_CREDENTIAL_MASTER_KEY]).size,3);
  assert.equal(values.APP_LLM_MASTER_KEY.length,64);
  assert.equal(values.APP_CREDENTIAL_MASTER_KEY.length,64);
  assert.ok(values.APP_INITIAL_ADMIN_PASSWORD.length <= 64);
  if (process.platform !== 'win32') assert.equal(fs.statSync(path.join(root,'.env')).mode & 0o777,0o600);
  fs.writeFileSync(path.join(root,'config/application.yml'),'app:\n  security:\n    session-idle-minutes: 61\n');
  fs.writeFileSync(path.join(root,'data/sentinel'),'business data');
  initialize(root,'analyzer-coder:new-package');
  assert.equal(fs.readFileSync(path.join(root,'.env'),'utf8'),env);
  initialize(root,'analyzer-coder:upgrade',true);
  assert.equal(fs.readFileSync(path.join(root,'.env'),'utf8'),env.replace('ANALYZER_IMAGE=analyzer-coder:install','ANALYZER_IMAGE=analyzer-coder:upgrade'));
  assert.match(fs.readFileSync(path.join(root,'config/application.yml'),'utf8'),/61/);
  assert.equal(fs.readFileSync(path.join(root,'data/sentinel'),'utf8'),'business data');
});
test('invalid image tags cannot create configuration', t => {
  const root = temporary(t);
  assert.throws(() => initialize(root,'bad\nCOPY secret /secret'),/Invalid image/);
  assert.deepEqual(fs.readdirSync(root),[]);
});

function delivery(directory, image) {
  fs.mkdirSync(directory,{recursive:true});
  for (const name of ['analyzer.sh','analyzer.ps1','compose.yaml','README.md']) fs.copyFileSync(path.join(template,name),path.join(directory,name));
  fs.writeFileSync(path.join(directory,'image.env'),`ANALYZER_IMAGE=${image}\n`);
  fs.writeFileSync(path.join(directory,'image.tar'),'fixture image');
  fs.writeFileSync(path.join(directory,'MANIFEST.json'),JSON.stringify({image}));
  fs.writeFileSync(path.join(directory,'SHA256SUMS'),fs.readdirSync(directory).sort().map(name =>
    crypto.createHash('sha256').update(fs.readFileSync(path.join(directory,name))).digest('hex') + '  ' + name).join('\n')+'\n');
}
function mockDocker(root, work) {
  const bin = path.join(work,'bin');
  fs.mkdirSync(bin);
  const mock = path.join(bin,'docker.cjs');
  fs.writeFileSync(mock, `
const fs=require('node:fs'),path=require('node:path');
const args=process.argv.slice(2);
fs.appendFileSync(process.env.MOCK_CALLS,JSON.stringify(args)+'\\n');
const stateFile=process.env.MOCK_STATE;
const state=fs.existsSync(stateFile)?JSON.parse(fs.readFileSync(stateFile)):{images:[],container:false};
const save=()=>fs.writeFileSync(stateFile,JSON.stringify(state));
const out=value=>process.stdout.write(String(value)+'\\n');
(async()=>{
  if(args[0]==='info')return out('linux');
  if(args[0]==='image'&&args[1]==='inspect'){
    if(!state.images.includes(args[2]))process.exit(1);
    return out('linux/amd64');
  }
  if(args[0]==='load'){
    const packet=path.dirname(args[args.indexOf('-i')+1]);
    const image=fs.readFileSync(path.join(packet,'image.env'),'utf8').trim().split('=')[1];
    state.images.push(image);save();return out('Loaded fixture image');
  }
  if(args[0]==='run'&&args.includes('/opt/analyzer-coder/deploy/configure.mjs')){
    const i=args.indexOf('/opt/analyzer-coder/deploy/configure.mjs');
    const {initialize}=await import(${JSON.stringify(new URL('../deploy/all-in-one/configure.mjs',import.meta.url).href)});
    initialize(process.env.MOCK_ROOT,args[i+2],args.includes('--upgrade'));return;
  }
  if(args[0]==='inspect')return out(process.env.MOCK_FOREIGN==='1'?'another-installation':state.owner);
  if(args[0]==='compose'){
    if(args[1]==='version')return out('Docker Compose mock');
    if(args.includes('ps')&&args.includes('--quiet'))return state.container?out('fixture-container'):undefined;
    if(args.includes('up')){
      if(process.env.MOCK_FAIL_UP==='1')process.exit(7);
      state.container=true;state.owner=args[args.indexOf('--project-directory')+1];save();return;
    }
    return;
  }
  throw new Error('Unexpected docker command: '+JSON.stringify(args));
})().catch(e=>{process.stderr.write(e.message);process.exit(1)});
`);
  if(process.platform==='win32') fs.writeFileSync(path.join(bin,'docker.ps1'),`& '${process.execPath.replaceAll("'","''")}' '${mock.replaceAll("'","''")}' @args\nexit $LASTEXITCODE\n`);
  else {
    const quote=value=>"'"+value.replaceAll("'","'\\''")+"'";
    fs.writeFileSync(path.join(bin,'docker'),`#!/bin/sh\nexec ${quote(process.execPath.replaceAll('\\','/'))} ${quote(mock.replaceAll('\\','/'))} "$@"\n`,{mode:0o755});
  }
  const env={...process.env,PATH:bin+path.delimiter+process.env.PATH,MOCK_ROOT:root,MOCK_CALLS:path.join(work,'calls.jsonl'),MOCK_STATE:path.join(work,'state.json')};
  const powershell=process.platform==='win32';
  const invoke=(action,packet,extra={})=>spawnSync(powershell?'pwsh':'bash',
    powershell?['-NoProfile','-File',path.join(root,'analyzer.ps1'),action,...(packet?['-PackageDirectory',packet]:[])]:[path.join(root,'analyzer.sh').replaceAll('\\','/'),action,...(packet?[packet.replaceAll('\\','/')]:[])],
    {cwd:work,env:{...env,...extra},encoding:'utf8',timeout:60000,windowsHide:true});
  return {invoke,calls:env.MOCK_CALLS};
}
test('launcher installs from a directory with spaces; upgrade preserves data/config and rejects damaged packages or foreign installations', {timeout:180000}, t => {
  const work=temporary(t), root=path.join(work,'installed app'), newer=path.join(work,'new release');
  delivery(root,'analyzer-coder:old'); delivery(newer,'analyzer-coder:new');
  const {invoke,calls}=mockDocker(root,work);
  const started=invoke('start');
  assert.equal(started.status,0,started.error?.message ?? started.stderr+'\n'+started.stdout);
  const before=fs.readFileSync(path.join(root,'.env'),'utf8');
  fs.writeFileSync(path.join(root,'data/sentinel'),'keep');
  fs.writeFileSync(path.join(root,'config/application.yml'),'custom: true\n');
  const upgraded=invoke('upgrade',newer);
  assert.equal(upgraded.status,0,upgraded.error?.message ?? upgraded.stderr);
  assert.equal(fs.readFileSync(path.join(root,'.env'),'utf8'),before.replace('ANALYZER_IMAGE=analyzer-coder:old','ANALYZER_IMAGE=analyzer-coder:new'));
  assert.equal(fs.readFileSync(path.join(root,'data/sentinel'),'utf8'),'keep');
  assert.equal(fs.readFileSync(path.join(root,'config/application.yml'),'utf8'),'custom: true\n');
  assert.equal(fs.readFileSync(path.join(root,'image.env'),'utf8'),'ANALYZER_IMAGE=analyzer-coder:new\n');
  fs.writeFileSync(path.join(newer,'.incomplete'),'interrupted export');
  assert.notEqual(invoke('upgrade',newer).status,0,'incomplete delivery must be rejected');
  fs.unlinkSync(path.join(newer,'.incomplete'));
  const sums=fs.readFileSync(path.join(newer,'SHA256SUMS'),'utf8');
  fs.writeFileSync(path.join(newer,'SHA256SUMS'),sums.split('\n').filter(line=>!line.endsWith('  image.tar')).join('\n'));
  assert.notEqual(invoke('upgrade',newer).status,0,'image checksum must be present');
  fs.writeFileSync(path.join(newer,'SHA256SUMS'),sums);
  const foreign=invoke('stop',null,{MOCK_FOREIGN:'1'});
  assert.notEqual(foreign.status,0,'must not stop another installation');
  fs.appendFileSync(path.join(newer,'image.tar'),'corrupted');
  const saved=fs.readFileSync(path.join(root,'.env'),'utf8');
  assert.notEqual(invoke('upgrade',newer).status,0,'damaged delivery must be rejected');
  assert.equal(fs.readFileSync(path.join(root,'.env'),'utf8'),saved);
  assert.notEqual(invoke('start',null,{MOCK_FAIL_UP:'1'}).status,0,'failed readiness must propagate');
  const commands=fs.readFileSync(calls,'utf8').trim().split('\n').map(JSON.parse);
  assert.ok(commands.some(args=>args.includes('up')&&args.includes('--wait')&&args.includes('never')));
  assert.equal(commands.filter(args=>args[0]==='load').length,2,'only valid old/new images loaded');
});
