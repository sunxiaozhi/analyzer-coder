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
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'analyzer-image-test-'));
  t.after(() => {
    assert.equal(path.dirname(root),os.tmpdir());
    assert.ok(path.basename(root).startsWith('analyzer-image-test-'));
    fs.rmSync(root,{recursive:true,force:true});
  });
  fs.mkdirSync(path.join(root,'scripts'),{recursive:true});
  fs.mkdirSync(path.join(root,'deploy/all-in-one/config'),{recursive:true});
  fs.copyFileSync(path.join(repository,'scripts/build-docker-image.mjs'),path.join(root,'scripts/build-docker-image.mjs'));
  fs.writeFileSync(path.join(root,'scripts/smoke-docker-image.mjs'),'if(process.env.FAIL_SMOKE)process.exit(1);\n');
  for(const name of ['analyzer.sh','analyzer.ps1','compose.yaml','image.env','README.md']) fs.copyFileSync(path.join(repository,'deploy/all-in-one',name),path.join(root,'deploy/all-in-one',name));
  fs.writeFileSync(path.join(root,'deploy/all-in-one/.env'),'PRIVATE=not-for-delivery');
  fs.writeFileSync(path.join(root,'deploy/all-in-one/config/private.key'),'private');
  const preload=path.join(root,'docker.cjs');
  fs.writeFileSync(preload,`
const cp=require('node:child_process'),fs=require('node:fs');
const original=cp.spawnSync;
cp.spawnSync=function(command,args,options){
 if(command!=='docker')return original(command,args,options);
 if(args[0]==='save'){
  if(process.env.FAIL_SAVE)return {status:1,stderr:'simulated export failure'};
  fs.writeFileSync(args[args.indexOf('--output')+1],'fixture image');
 }
 const stdout=args[0]==='info'?'linux':args[0]==='image'?JSON.stringify({Os:'linux',Architecture:process.env.WRONG_ARCH?'arm64':'amd64',Id:'sha256:fixture',Created:'2026-10-08T00:00:00Z'}):'';
 return {status:0,stdout,stderr:''};
};
require('node:module').syncBuiltinESMExports();
`);
  const launch=(extra={},args=[])=>spawnSync(process.execPath,['--require',preload,path.join(root,'scripts/build-docker-image.mjs'),'--version','test-1','--output','delivery with spaces',...args],
    {cwd:os.tmpdir(),env:{...process.env,...extra},encoding:'utf8',windowsHide:true,timeout:30000});
  return {root,launch,destination:path.join(root,'delivery with spaces/analyzer-coder-docker-test-1')};
}
test('complete delivery includes launchers and archive, excludes private data, and hashes every delivery file',t=>{
  const f=fixture(t), result=f.launch();
  assert.equal(result.status,0,result.stderr);
  const expected=['MANIFEST.json','README.md','SHA256SUMS','analyzer.ps1','analyzer.sh','compose.yaml','image.env','image.tar'];
  assert.deepEqual(fs.readdirSync(f.destination).sort(),expected.sort());
  for(const line of fs.readFileSync(path.join(f.destination,'SHA256SUMS'),'utf8').trim().split('\n')){
    const [,sum,name]=line.match(/^([0-9a-f]{64})  (.+)$/);
    assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(f.destination,name))).digest('hex'),sum);
  }
  assert.match(fs.readFileSync(path.join(f.destination,'image.env'),'utf8'),/analyzer-coder:test-1/);
  const manifest=JSON.parse(fs.readFileSync(path.join(f.destination,'MANIFEST.json')));
  assert.equal(manifest.dataMount,'./data:/data');
  assert.equal(manifest.configMount,'./config:/config:ro');
  const archive=f.destination+'.tar.gz';
  assert.ok(fs.existsSync(archive));
  assert.equal(fs.readFileSync(archive+'.sha256','utf8').split(' ')[0],crypto.createHash('sha256').update(fs.readFileSync(archive)).digest('hex'));
  const entries=spawnSync('tar',['-tzf',archive],{encoding:'utf8',windowsHide:true});
  assert.equal(entries.status,0,entries.stderr);
  assert.doesNotMatch(entries.stdout,/private\.key|\/\.env$|\.incomplete/);
  assert.notEqual(f.launch().status,0,'existing releases must not be overwritten');
});
test('failed image export leaves an incomplete marker and no archive',t=>{
  const f=fixture(t);
  assert.notEqual(f.launch({FAIL_SAVE:'1'}).status,0);
  assert.ok(fs.existsSync(path.join(f.destination,'.incomplete')));
  assert.ok(!fs.existsSync(f.destination+'.tar.gz'));
});
test('failed runtime acceptance or wrong architecture produces no delivery',t=>{
  for(const setting of [{FAIL_SMOKE:'1'},{WRONG_ARCH:'1'}]){
    const f=fixture(t);
    assert.notEqual(f.launch(setting).status,0);
    assert.ok(!fs.existsSync(f.destination));
  }
});
test('removed application-only flags and unsafe tags are rejected',t=>{
  const f=fixture(t);
  for(const args of [['--without-images'],['--skip-build'],['--version','bad/version']])assert.notEqual(f.launch({},args).status,0);
  assert.ok(!fs.existsSync(f.destination));
});
