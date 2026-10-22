import assert from 'node:assert/strict';
import test from 'node:test';
import {loadC23PostgresParent,validatePostgresC36Runtime,validateC36GosuBuildInfo,
  effectivePostgresC36Summary,PARENT_REFERENCE,PARENT_CONFIG_ID,PARENT_ACCEPTANCE_SHA256,
  PARENT_RUNTIME_SHA256,GOSU_READER,GOSU_SOURCE,GOSU_SOURCE_SHA256} from './postgres-c36-adjudication.mjs';

const image='sha256:'+'a'.repeat(64);
const newBinary='b'.repeat(64);
const revision='c'.repeat(40);
const buildInfo='/tmp/gosu: go1.27.3\n\tpath\tgithub.com/tianon/gosu\n\tbuild\t-trimpath=true\n'+
  '\tbuild\tCGO_ENABLED=0\n\tbuild\tGOARCH=amd64\n\tbuild\tGOOS=linux\n';

async function fixture(){
  const {parent}=await loadC23PostgresParent();
  const candidate=structuredClone(parent);
  candidate.reference=image;candidate.imageId=image;candidate.rootfs=[...parent.rootfs,image];
  candidate.configuration.Labels={...parent.configuration.Labels,
    'com.otziv.publication.revision':revision,
    'com.otziv.gosu.source-sha256':GOSU_SOURCE_SHA256,
    'com.otziv.gosu.go':'1.27.3','com.otziv.security.proof':'postgres-c36-gosu-go1273'};
  candidate.inventory['usr/local/bin/gosu'].sha256=newBinary;
  const record={schema:'otziv-postgres-c36-inspection-v1',result:'PASS',component:'postgres',
    productionAccess:false,ownedContainersRemaining:0,
    parentInspectorSha256:'4969ebbd33456ba2fa10e377d2ff6877dca7651334c33536ca03362f83af1e1d',
    candidate};
  const config={rootfs:{diff_ids:candidate.rootfs},config:candidate.configuration};
  const report={Metadata:{ImageID:image,ImageConfig:config}};
  return {parent,record,config,report};
}

test('C36 accepts exactly one gosu replacement over frozen C23',async()=>{
  const {parent,record,config,report}=await fixture();
  assert.deepEqual(validatePostgresC36Runtime(record,parent,config,report,revision),
    {binarySha256:newBinary,changedFiles:['usr/local/bin/gosu']});
});

for(const [name,tamper,error] of [
  ['another executable',(x)=>{x.record.candidate.inventory['usr/local/pgsql/bin/postgres'].sha256='d'.repeat(64);},'unrelated_files_changed'],
  ['database package',(x)=>{x.record.candidate.captured['var/lib/dpkg/status']='tampered';},'package_database_changed'],
  ['runtime command',(x)=>{x.record.candidate.configuration.Cmd=['postgres','--single'];},'launch_contract_changed'],
  ['missing parent layer',(x)=>{x.record.candidate.rootfs[0]='sha256:'+'e'.repeat(64);x.config.rootfs.diff_ids[0]=x.record.candidate.rootfs[0];},'parent_layers_changed'],
  ['unreviewed label',(x)=>{x.record.candidate.configuration.Labels['com.otziv.other']='yes';},'labels_changed'],
  ['gosu mode',(x)=>{x.record.candidate.inventory['usr/local/bin/gosu'].mode=0o777;},'gosu_mode'],
])test('C36 rejects '+name,async()=>{
  const x=await fixture();tamper(x);
  assert.throws(()=>validatePostgresC36Runtime(x.record,x.parent,x.config,x.report,revision),new RegExp('postgres_c36_'+error));
});

test('Go build info is checked from the copied binary',()=>{
  assert.equal(validateC36GosuBuildInfo(buildInfo).goVersion,'go1.27.3');
  assert.throws(()=>validateC36GosuBuildInfo(buildInfo.replace('go1.27.3','go1.27.1')),
    /postgres_c36_gosu_go_version/);
  assert.throws(()=>validateC36GosuBuildInfo(buildInfo.replace('CGO_ENABLED=0','CGO_ENABLED=1')),
    /postgres_c36_gosu_cgo/);
});

test('C36 verdict stays fail closed unless all binary and parent bindings match',()=>{
  const summary={high:0,critical:0,blockingFixedHighOrCritical:0,unfixedHighOrCritical:0};
  const receipt={schema:'otziv-postgres-c36-adjudication-v1',status:'EXACT_RUNTIME_VERIFIED',
    parentReference:PARENT_REFERENCE,parentConfigId:PARENT_CONFIG_ID,
    parentAcceptanceSha256:PARENT_ACCEPTANCE_SHA256,parentRuntimeSha256:PARENT_RUNTIME_SHA256,
    reviewSha256:'c52ea7697261a4a1d3eed1540f14aa9048f44a63ff0631dd349443905e09fd67',
    validUntil:'2027-01-01T00:00:00Z',runtimeProofSha256:'d'.repeat(64),
    changedFiles:['usr/local/bin/gosu'],inspectedBinaryExecuted:false,decisions:[],libxmlSeptember:null,
    gosu:{goVersion:'go1.27.3',module:'github.com/tianon/gosu',reader:GOSU_READER,
      source:GOSU_SOURCE,sourceSha256:GOSU_SOURCE_SHA256,binarySha256:newBinary,
      inspectedBinaryExecuted:false,buildInfoOutput:buildInfo}};
  assert.equal(effectivePostgresC36Summary(summary,receipt).result,'PASS');
  assert.equal(effectivePostgresC36Summary(summary,{...receipt,status:'REJECTED'}).result,'FAIL');
  assert.throws(()=>effectivePostgresC36Summary(summary,{...receipt,changedFiles:['usr/local/bin/gosu','bin/sh']}),
    /postgres_c36_summary_scope/);
});
