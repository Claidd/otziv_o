import test from 'node:test';
import assert from 'node:assert/strict';
import {requireUpgradeCheck,requireGracefulAppStop} from './mysql-upgrade-rehearsal.mjs';

const complete=()=>({serverVersion:'9.0.0',targetVersion:'9.7.3',errorCount:0,warningCount:2,noticeCount:1,
  checksPerformed:[{id:'syntax',status:'OK'},{id:'reservedKeywords',status:'OK'}],manualChecks:[{title:'Review configuration'}]});
test('completed checker output retains warnings and manual work without inventing zero risk',()=>{
  assert.deepEqual(requireUpgradeCheck(complete()),{errors:0,warnings:2,notices:1,completedChecks:2,manualChecks:[{title:'Review configuration'}]});
});
for(const [name,change] of [
  ['wrong source',value=>{value.serverVersion='8.4.0';}],
  ['wrong target',value=>{value.targetVersion='9.7.0';}],
  ['reported compatibility errors',value=>{value.errorCount=1;}],
  ['missing error count',value=>{delete value.errorCount;}],
  ['empty checks',value=>{value.checksPerformed=[];}],
  ['failed check despite zero aggregate errors',value=>{value.checksPerformed[0].status='ERROR';}],
  ['incomplete check',value=>{value.checksPerformed[0].status='SKIPPED';}]
])test(name+' prevents the in-place upgrade gate from passing',()=>{
  const value=complete();change(value);assert.throws(()=>requireUpgradeCheck(value));
});

const gracefulLog='Graceful shutdown complete\nClosing JPA EntityManagerFactory\nHikariDataSource - primary - Shutdown initiated...\nHikariDataSource - primary - Shutdown completed.';
const stopped=()=>({pid1:'java',exitCode:143,oomKilled:false,elapsedMs:14000});
test('SIGTERM exit143 requires actual completed Spring, JPA and pool shutdown',()=>{
  assert.deepEqual(requireGracefulAppStop(stopped(),gracefulLog,120000),{springComplete:true,entityManagerClosed:true,poolsClosed:1});
});
test('forced kill, shell PID1, OOM and expired deadline each fail closed',()=>{
  for(const delta of [{exitCode:137},{pid1:'sh'},{oomKilled:true},{elapsedMs:120000}])assert.throws(()=>requireGracefulAppStop({...stopped(),...delta},gracefulLog,120000));
});
test('missing shutdown evidence or a second unfinished pool cannot pass',()=>{
  for(const log of ['',gracefulLog.replace('Graceful shutdown complete',''),gracefulLog.replace('Closing JPA EntityManagerFactory',''),gracefulLog+'\nHikariDataSource - secondary - Shutdown initiated...'])assert.throws(()=>requireGracefulAppStop(stopped(),log,120000));
});
