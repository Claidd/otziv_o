import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateRegistry} from './verify-remediation-registry.mjs';
const fixture=()=>({schema:'otziv-architecture-finding-registry-v1',findings:Array.from({length:20},(_,i)=>({id:'F'+String(i+1).padStart(2,'0'),packages:['P21'],implementationOwner:'Fixture implementation owner',releaseOwnerRole:'Release owner',status:'LOCAL_VERIFICATION',migrations:[],evidence:['fixture.json'],remainingAcceptance:['Production acceptance'],pullRequest:null,pullRequestMissingReason:'No authenticated publication',productionReleased:false}))});
test('a complete inventory may honestly retain uncompleted acceptance',()=>assert.equal(validateRegistry(fixture()).length,20));
test('a missing finding cannot disappear from the closure report',()=>{const f=fixture();f.findings.pop();assert.throws(()=>validateRegistry(f));});
test('replacing an unresolved record with CLOSED fails',()=>{const f=fixture();f.findings[0].status='CLOSED';assert.throws(()=>validateRegistry(f));});
test('a real PR alone does not prove completed acceptance',()=>{const f=fixture();Object.assign(f.findings[0],{status:'CLOSED',pullRequest:'https://github.com/Claidd/otziv_o/pull/1',releaseOwner:'Fixture reviewer',productionReleased:true,acceptanceEvidence:['fixture']});assert.throws(()=>validateRegistry(f));});
