import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,symlink,rm,rmdir} from 'node:fs/promises';
import {join,resolve,relative,sep,isAbsolute} from 'node:path';
import {tmpdir} from 'node:os';
import {createEvidenceReader} from './reviewed-image-defaults.mjs';

const context='infrastructure/keycloak/security-generation/c14-migration-fix';
const kind=process.platform==='win32'?'junction':'dir';
async function workspace(action){
  const owned=await mkdtemp(join(tmpdir(),'otziv-keycloak-reader-'));
  const root=join(owned,'repository');
  try{
    await mkdir(join(root,'infrastructure/runtime-security'),{recursive:true});
    await action(root,owned);
  }finally{
    const inside=relative(resolve(tmpdir()),resolve(owned));
    assert.ok(inside&&inside!=='..'&&!inside.startsWith('..'+sep)&&!isAbsolute(inside));
    await rm(owned,{recursive:true,force:true});
  }
}

test('fixed migration context can supply immutable proof bytes without granting access to adjacent source',async()=>{
  await workspace(async root=>{
    await mkdir(join(root,context,'proofs'),{recursive:true});
    await writeFile(join(root,context,'proofs/review.json'),'{}');
    const read=await createEvidenceReader(root);
    assert.equal((await read(context+'/proofs/review.json')).toString(),'{}');
    for(const path of [context+'-foreign/proofs/review.json',context+'/../private.json',
      context+'/proofs/escape:ads',context+'/proofs\\review.json','infrastructure/keycloak/private.json']){
      await assert.rejects(read(path),/path_outside_evidence_scope/);
    }
  });
});

test('migration context symlink cannot reference a directory outside the repository',async()=>{
  await workspace(async(root,owned)=>{
    const outside=join(owned,'outside');await mkdir(outside);await writeFile(join(outside,'review.json'),'{}');
    await mkdir(join(root,'infrastructure/keycloak/security-generation'),{recursive:true});
    await symlink(outside,join(root,context),kind);
    const read=await createEvidenceReader(root);
    await assert.rejects(read(context+'/review.json'),/root_symlink_escape/);
  });
});

test('nested proof symlink cannot cross from migration context into another allowed root',async()=>{
  await workspace(async root=>{
    await mkdir(join(root,context),{recursive:true});
    await writeFile(join(root,'infrastructure/runtime-security/review.json'),'{}');
    await symlink(join(root,'infrastructure/runtime-security'),join(root,context,'proofs'),kind);
    const read=await createEvidenceReader(root);
    await assert.rejects(read(context+'/proofs/review.json'),/evidence_symlink_escape/);
  });
});

for(const prefix of [context,'infrastructure/runtime-security']){
  test('whole allowed root cannot redirect to unrelated source inside repository: '+prefix,async()=>{
    await workspace(async root=>{
      const outside=join(root,'backend/private');await mkdir(outside,{recursive:true});
      await writeFile(join(outside,'synthetic-only.txt'),'synthetic fixture');
      if(prefix==='infrastructure/runtime-security')await rmdir(join(root,prefix));
      else await mkdir(join(root,'infrastructure/keycloak/security-generation'),{recursive:true});
      await symlink(outside,join(root,prefix),kind);
      await assert.rejects(async()=>{
        const read=await createEvidenceReader(root);await read(prefix+'/synthetic-only.txt');
      },/root_symlink_escape/);
    });
  });
}
